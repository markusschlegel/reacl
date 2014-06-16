(ns ^{:author "Michael Sperber"
      :doc "Supporting macros for Reacl."}
  reacl.core
  (:refer-clojure :exclude [class]))

(def ^{:private true} lifecycle-name-map
  { ;; 'component-will-mount is in special-tags
   'component-did-mount 'componentDidMount
   'component-will-receive-props 'componentWillReceiveProps
   'should-component-update? 'shouldComponentUpdate
   'component-will-update 'componentWillUpdate
   'component-did-update 'componentDidUpdate
   'component-will-unmount 'componentWillUnmount})

(def ^{:private true} special-tags
  (clojure.set/union (into #{} (map val lifecycle-name-map))
                     #{'render 'handle-message 'initial-state 'component-will-mount 'local}))

(defmacro class
  "Create a Reacl class.

  This is a regular React class, with some convenience added - binding
  of this, app-state, parameters etc - and implementing Reacl-specific
  protocols, particularly for event handling.

  The syntax is

  (reacl.core/class <name> [<this-name> [<app-state-name> [<local-state-name>]]] [<param> ...]
    render <renderer-exp>
    [initial-state <initial-state-exp>]
    [local [<local-name> <local-expr>]]
    [handle-message <messager-handler-exp>]
    [<lifecycle-method-name> <lifecycle-method-exp> ...])

  <name> is a name for the class, for debugging purposes.

  A number of names are bound in the various expressions in the body
  of reacl.core/class:

  - <this-name> is bound to the component object itself
  - <app-state-name> is bound to the global application state
  - <local-state-name> is bound to the componnet-local state
  - the <param> ... names are the explicit arguments of instantiations

  A `local` clause allows binding additional local variables upon
  instantiation.  The syntax is analogous to `let`.

  <renderer-exp> is an expression that renders the component, and
  hence must return a virtual dom node.

  The handle-message function accepts a message sent to the
  component via reacl.core/send-message!.  It's expected to
  return a value specifying a new application state and/or
  component-local state, via reacl.core/return.

  A lifecycle method can be one of:

    component-will-mount component-did-mount
    component-will-receive-props should-component-update? 
    component-will-update component-did-update component-will-unmount

  These correspond to React's lifecycle methods, see here:

  http://facebook.github.io/react/docs/component-specs.html

  Each right-hand-side <lifecycle-method-exp>s should evaluate to a
  function.  This function's argument is always the component.  The
  remaining arguments are as for React.

  Example:

  (defrecord New-text [text])
  (defrecord Submit [])

  (reacl/defclass to-do-app
    this todos local-state []
    render
    (dom/div
     (dom/h3 \"TODO\")
     (dom/div (map-indexed (fn [i todo]
			     (dom/keyed (str i) (to-do-item this (lens/at-index i))))
			   todos))
     (dom/form
      {:onSubmit (fn [e _]
		   (.preventDefault e)
		   (reacl/send-message! this (Submit.)))}
      (dom/input {:onChange (fn [e]
			      (reacl/send-message! this
						   (New-text. (.. e -target -value))))
		  :value local-state})
      (dom/button
       (str \"Add #\" (+ (count todos) 1)))))

    initial-state \"\"

    handle-message
    (fn [msg]
      (cond
       (instance? New-text msg)
       (reacl/return :local-state (:text msg))

       (instance? Submit msg)
       (reacl/return :local-state \"\"
		     :app-state (concat todos [(Todo. local-state false)])))))"
  [?name & ?stuff]

  (let [[?component ?stuff] (if (symbol? (first ?stuff))
                              [(first ?stuff) (rest ?stuff)]
                              [`component# ?stuff])
        ;; FIXME abstract
        [?app-state ?stuff] (if (symbol? (first ?stuff))
                              [(first ?stuff) (rest ?stuff)]
                              [`app-state# ?stuff])
        [?local-state ?stuff] (if (symbol? (first ?stuff))
                                [(first ?stuff) (rest ?stuff)]
                                [`local-state# ?stuff])
        [[& ?args] & ?clauses] ?stuff

        clause-map (apply hash-map ?clauses)
        wrap-args
        (fn [?this & ?body]
          `(let [~?component ~?this ; FIXME: instead bind ?component directly
                 ~?app-state (reacl.core/extract-app-state ~?this)
                 [~@?args] (reacl.core/extract-args ~?this)] ; FIXME: what if empty?
             ~@?body))
        ?locals-clauses (partition 2 (get clause-map 'local []))
        ?initial-state (let [?state-expr (get clause-map 'initial-state)]
                          (if (or ?state-expr (not (empty? ?locals-clauses)))
                            (let [?this `this#]
                              `(fn [] 
                                 (cljs.core/this-as
                                  ~?this
                                  ~(wrap-args 
                                    ?this
                                    `(reacl.core/make-local-state [~@(map second ?locals-clauses)]
                                                                  ~(or ?state-expr `nil))))))
                            `(fn [] (reacl.core/make-local-state nil nil))))

        wrap-args&locals
        (fn [?this & ?body]
          (wrap-args ?this
                     `(let [~?local-state (reacl.core/extract-local-state ~?this)
                            [~@(map first ?locals-clauses)] (reacl.core/extract-locals ~?this)]
                        ~@?body)))

        misc (filter (fn [e]
                       (not (contains? special-tags (key e))))
                     clause-map)
        lifecycle (filter (fn [e]
                            (contains? lifecycle-name-map (key e)))
                          clause-map)
        ?renderfn
        (let [?this `this#  ; looks like a bug in ClojureScript, this# produces a warning but works
              ?state `state#
              ?render (get clause-map 'render)]
          `(fn []
             (cljs.core/this-as 
              ~?this
              (let [~?state (reacl.core/extract-local-state ~?this)]
                ~(wrap-args&locals
                  ?this
                  `(let [~@(mapcat (fn [p]
                                     [(first p) `(aget ~?this ~(str (first p)))])
                                    misc)]
                     ~?render))))))]
    `(let [clazz#
           (js/React.createClass (cljs.core/js-obj "render" ~?renderfn 
                                                   "getInitialState" ~?initial-state 
                                                   "displayName" ~(str ?name)
                                                   ~@(mapcat (fn [[?name ?rhs]]
                                                               (let [?args `args#
                                                                     ?this `this#]
                                                                 [(str (get lifecycle-name-map ?name))
                                                                  `(fn [& ~?args]
                                                                     (cljs.core/this-as
                                                                      ;; FIXME: should really bind ?rhs outside
                                                                      ~?this
                                                                      (apply ~(wrap-args&locals ?this ?rhs) ~?args)))]))
                                                             lifecycle)
                                                   ~@(mapcat (fn [[?name ?rhs]]
                                                               [(str ?name) 
                                                                (let [?args `args#
                                                                      ?this `this#]
                                                                  `(fn [& ~?args]
                                                                     (cljs.core/this-as
                                                                      ~?this
                                                                      (apply ~(wrap-args&locals ?this ?rhs) ~?this ~?args))))])
                                                             misc)
                                                   ;; message handler, if there's one specified
                                                   ~@(if-let [?handler (get clause-map 'handle-message)]
                                                       ["__handleMessage"
                                                        (let [?this `this#]
                                                          `(fn [msg#]
                                                             (cljs.core/this-as
                                                              ~?this
                                                              (~(wrap-args&locals ?this ?handler) msg#))))]
                                                       [])
                                                   ;; event handler, if there's a handle-message clause
                                                   ~@(if (contains? clause-map 'handle-message)
                                                       ["componentWillMount"
                                                        (let [?this `this#]
                                                          `(fn []
                                                             (cljs.core/this-as
                                                              ~?this
                                                              (do
                                                                (reacl.core/message-processor ~?this)
                                                                ;; if there is a component-will-mount clause, tack it on
                                                                ~@(if-let [?will-mount (get clause-map 'component-will-mount)]
                                                                    [`(~(wrap-args&locals ?this ?will-mount) ~?this)]
                                                                    [])))))]
                                                       [])))]
       (fn [component# & args#]
         (apply reacl.core/instantiate clazz# component# args#)))))

(defmacro defclass
  "Define a Reacl class.

   The syntax is

   (reacl.core/defclass <name> <app-state> [<param> ...]
     render <renderer-exp>
     [initial-state <initial-state-exp>]
     [<lifecycle-method-name> <lifecycle-method-exp> ...]
     [handle-message <messager-handler-exp>]

     <event-handler-name> <event-handler-exp> ...)

   This expands to this:

   (def <name>
     (reacl.core/class <name> <app-state> [<param> ...]
       render <renderer-exp>
       [initial-state <initial-state-exp>]
       [<lifecycle-method-name> <lifecycle-method-exp> ...]
       [handle-message <messager-handler-exp>]

       <event-handler-name> <event-handler-exp> ...))"
  [?name & ?stuff]
  `(def ~?name (reacl.core/class ~?name ~@?stuff)))
