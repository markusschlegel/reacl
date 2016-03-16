(ns ^{:doc "Supporting macros for Reacl."}
  reacl.core
  (:require [clojure.set :as set])
  (:refer-clojure :exclude [class]))

(def ^{:private true} lifecycle-name-map
  {'component-will-mount 'componentWillMount
   'component-did-mount 'componentDidMount
   'component-will-receive-args 'componentWillReceiveProps
   'should-component-update? 'shouldComponentUpdate
   'component-will-update 'componentWillUpdate
   'component-did-update 'componentDidUpdate
   'component-will-unmount 'componentWillUnmount})

;; Attention: duplicate definition in core.cljs
(def ^{:private true} special-tags
  (set/union (into #{} (map val lifecycle-name-map))
             #{'render 'handle-message 'initial-state 'component-will-mount 'local 'mixins}))

(defn- split-symbol [stuff dflt]
  (if (symbol? (first stuff))
    [(first stuff) (rest stuff)]
    [dflt stuff]))

(defmacro class
  "Create a Reacl class.

  The syntax is

      (reacl.core/class <name> [<this-name> [<app-state-name> [<local-state-name>]]] [<param> ...]
        render <renderer-exp>
        [initial-state <initial-state-exp>]
        [local [<local-name> <local-expr>]]
        [handle-message <messager-handler-exp>]
        [<lifecycle-method-name> <lifecycle-method-exp> ...])

  `<name>` is a name for the class, for debugging purposes.

  A number of names are bound in the various expressions in the body
  of reacl.core/class:

  - `<this-name>` is bound to the component object itself
  - `<app-state-name>` is bound to the global application state
  - `<local-state-name>` is bound to the component-local state
  - the `<param>` ... names are the explicit arguments of instantiations

  A `local` clause allows binding additional local variables upon
  instantiation.  The syntax is analogous to `let`.

  `<renderer-exp>` is an expression that renders the component, and
  hence must return a virtual dom node.

  The `handle-message` function accepts a message sent to the
  component via [[reacl.core/send-message!]].  It's expected to
  return a value specifying a new application state and/or
  component-local state, via [[reacl.core/return]].

  A class can be invoked to yield a component as a function as follows:

  `(<class> <app-state> <reaction> <arg> ...)`

  In this invocation, the value of `<app-state>` will be the initial app
  state, `<reaction>` must evaluate to a *reaction* (see
  [[reacl.core.reaction]]) that gets invoked when the component's app
  state changes, and the `<arg>`s get evaluated to the `<param>`s.

  A lifecycle method can be one of:

    `component-will-mount` `component-did-mount`
    `component-will-receive-args` `should-component-update?`
    `component-will-update` `component-did-update` `component-will-unmount`

  These correspond to React's lifecycle methods, see
  here:

  http://facebook.github.io/react/docs/component-specs.html

  (`component-will-receive-args` is similar to `componentWillReceiveProps`.)

  Each right-hand-side `<lifecycle-method-exp>`s should evaluate to a
  function. The arguments, which slightly differ from the
  corresponding React methods, can be seen in the following list:

  `(component-will-mount)` The component can send itself messages in
  this method, or optionally return a new state
  via [[reacl.core/return]]. If that changes the state, the component
  will only render once.

  `(component-did-mount)` The component can update it's DOM in this method.

  `(component-will-receive-args next-arg1 next-arg2 ...)` The
  component has the chance to update it's local state in this method
  by sending itself a message or optionally return a new state
  via [[reacl.core/return]].

  `(should-component-update? next-app-state next-local-state next-arg1
  next-arg2 ...)` This method should return if the given new values
  should cause an update of the component (if render should be
  evaluated again). If it's not specified, a default implementation
  will do a (=) comparison with the current values. Implement this, if
  you want to prevent an update on every app-state change for example.

  `(component-will-update next-app-state next-local-state next-arg1 next-arg2 ...)`
  Called immediately before an update.
  
  `(component-did-update prev-app-state prev-local-state prev-arg1 prev-arg2 ...)`
  Called immediately after an update. The component can update it's DOM here.
  
  `(component-will-unmount)`
  The component can cleanup it's DOM here for example.

  Example:

    (defrecord New-text [text])
    (defrecord Submit [])
    (defrecord Change [todo])

    (reacl/defclass to-do-app
      this app-state local-state []
      render
      (dom/div
       (dom/h3 \"TODO\")
       (dom/div (map (fn [todo]
                       (dom/keyed (str (:id todo))
                                  (to-do-item
                                   todo
                                   (reacl/reaction this ->Change)
                                   this)))
                     (:todos app-state)))
       (dom/form
        {:onSubmit (fn [e _]
                     (.preventDefault e)
                     (reacl/send-message! this (Submit.)))}
        (dom/input {:onChange 
                    (fn [e]
                      (reacl/send-message!
                       this
                       (New-text. (.. e -target -value))))
                    :value local-state})
        (dom/button
         (str \"Add #\" (:next-id app-state)))))

      initial-state \"\"

      handle-message
      (fn [msg]
        (cond
         (instance? New-text msg)
         (reacl/return :local-state (:text msg))

         (instance? Submit msg)
         (let [next-id (:next-id app-state)]
           (reacl/return :local-state \"\"
                         :app-state
                         (assoc app-state
                           :todos
                           (concat (:todos app-state)
                                   [(Todo. next-id local-state false)])
                           :next-id (+ 1 next-id))))

         (instance? Delete msg)
         (let [id (:id (:todo msg))]
           (reacl/return :app-state
                         (assoc app-state
                           :todos 
                           (remove (fn [todo] (= id (:id todo)))
                                   (:todos app-state)))))

         (instance? Change msg)
         (let [changed-todo (:todo msg)
               changed-id (:id changed-todo)]
           (reacl/return :app-state
                         (assoc app-state
                           :todos (mapv (fn [todo]
                                          (if (= changed-id (:id todo) )
                                            changed-todo
                                            todo))
                                        (:todos app-state))))))))"
  [?name & ?stuff]

  (let [[?component ?stuff] (split-symbol ?stuff `component#)
        [?app-state ?stuff] (split-symbol ?stuff `app-state#)
        [?local-state ?stuff] (split-symbol ?stuff `local-state#)

        [[& ?args] & ?clauses] ?stuff

        ?clause-map (apply hash-map ?clauses)
        ?locals-clauses (get ?clause-map 'local [])
        ?locals-ids (map first (partition 2 ?locals-clauses))

        ?wrap-expression (fn [?sym]
                           (let [?exists (contains? ?clause-map ?sym)
                                 ?expr (get ?clause-map ?sym)]
                             (when ?exists
                               `(fn [] ~?expr))))
        ?render-fn (?wrap-expression 'render)
        ?initial-state-fn (?wrap-expression 'initial-state)

        ?other-fns-map (dissoc ?clause-map 'local 'render 'initial-state 'mixins)
        ?misc-fns-map (apply dissoc ?other-fns-map
                             special-tags)

        ?wrap-nlocal
        (fn [?f]
          (if ?f
            (let [?more `more#]
              `(fn [~?component ~?app-state [~@?locals-ids] [~@?args] & ~?more]
                 (apply ~?f ~?more)))
            'nil))
        ?wrap-std ;; reuse wrap-nlocal?!
        (fn [?f]
          (if ?f
            (let [?more `more#]
              `(fn [~?component ~?app-state ~?local-state [~@?locals-ids] [~@?args] & ~?more]
                 ;; every user misc fn is also visible
                 (let [~@(mapcat (fn [[n f]] [n `(aget ~?component ~(str n))]) ?misc-fns-map)]
                   (apply ~?f ~?more))))
            'nil))

        ?std-fns-map (assoc ?other-fns-map
                       'render ?render-fn)

        ?wrapped-nlocals [['initial-state (?wrap-nlocal ?initial-state-fn)]]

        ?wrapped-std (map (fn [[?n ?f]] [?n (?wrap-std ?f)])
                          ?std-fns-map)

        ?fns
        (into {}
              (map (fn [[?n ?f]] [(keyword ?n) ?f])
                   (concat ?wrapped-nlocals ?wrapped-std)))

        ;; compile an argument to a mixin to a function of this
        compile-argument (fn [thing]
                           (if-let [index (first (filter identity (map-indexed (fn [i x] (if (= x thing) i nil)) ?args)))]
                             `(fn [this#]
                                (nth (reacl.core/extract-args this#) ~index))
                             (throw (Error. (str "illegal mixin argument: " thing)))))

        ?mixins (if-let [mixins (get ?clause-map 'mixins)]
                  (map (fn [mix]
                         `(~(first mix) ~@(map compile-argument (rest mix))))
                       mixins)
                  nil)

        ?compute-locals
        `(fn [~?app-state [~@?args]]
           (let ~?locals-clauses
             [~@?locals-ids]))
        ]
    `(reacl.core/create-class ~?name ~(if ?mixins `[~@?mixins] `nil)  ~?compute-locals ~?fns)))

(defmacro defclass
  "Define a Reacl class, see [[class]] for documentation.

  The syntax is

      (reacl.core/defclass <name> [<this-name> [<app-state-name> [<local-state-name>]]] [<param> ...]
        render <renderer-exp>
        [initial-state <initial-state-exp>]
        [<lifecycle-method-name> <lifecycle-method-exp> ...]
        [handle-message <messager-handler-exp>]

        <event-handler-name> <event-handler-exp> ...)

  This expands to this:

      (def <name>
        (reacl.core/class <name> [<this-name> [<app-state-name> [<local-state-name>]]] [<param> ...]
          render <renderer-exp>
          [initial-state <initial-state-exp>]
          [<lifecycle-method-name> <lifecycle-method-exp> ...]
          [handle-message <messager-handler-exp>]

          <event-handler-name> <event-handler-exp> ...))"
  [?name & ?stuff]
  `(def ~?name (reacl.core/class ~(str ?name) ~@?stuff)))

(defmacro view
  "Create a Reacl view, which is like a class, but exposes no state (applications state).
   Views can be instantiated in the same way as virtual dom elements.

  The syntax is

      (reacl.core/view <name> [<this-name> [<local-state-name>]] [<param> ...]
        render <renderer-exp>
        [initial-state <initial-state-exp>]
        [local [<local-name> <local-expr>]]
        [handle-message <messager-handler-exp>]
        [<lifecycle-method-name> <lifecycle-method-exp> ...])

  `<name>` is a name for the view, for debugging purposes.

  A number of names are bound in the various expressions in the body
  of reacl.core/class:

  - `<this-name>` is bound to the component object itself
  - `<local-state-name>` is bound to the component-local state
  - the `<param>` ... names are the explicit arguments of instantiations

  The `local` and `<renderer-exp>` clauses are identical
  to those of the [[class]] macro.

  The `handle-message` function accepts a message sent to the
  component via [[reacl.core/send-message!]].  It's expected to return
  a value specifying a new component-local state,
  via [[reacl.core/return]]. Specifying an `:app-state` clause
  in [[reacl.core/return]] is not allowed.

  A view can be invoked to yield a component as a function as follows:

  `(<view> <arg> ...)`

  A lifecycle method can be one of:

    `component-will-mount` `component-did-mount`
    `component-will-receive-args` `should-component-update?`
    `component-will-update` `component-did-update` `component-will-unmount`

  The methods `component-did-mount` and `component-will-unmount` are
  the same as for classes. The methods `component-will-mount` and
  `component-will-receive-args` have the same arguments as for
  classes, but only the local component state may be updated by them.

  The methods `should-component-update?`, `component-did-update` and
  `component-will-unmount` are similar to those of [[class]], without
  the application state arguments. Specifically:

  - `(should-component-update? next-local-state next-arg1 next-arg2 ...)`

  - `(component-did-update prev-local-state prev-arg1 prev-arg2 ...)`

  - `(component-will-update next-local-state next-arg1 next-arg2 ...)`
  "
  [?name & ?stuff]
  (let [[?component ?stuff] (split-symbol ?stuff `component#)
        ?app-state `app-state#
        [?local-state ?stuff] (split-symbol ?stuff `local-state#)

        [[& ?args] & ?clauses] ?stuff

        ;; this adds app-state arg to component-will-update,
        ;; component-did-update, should-component-update?
        add-arg (fn [?current-fn]
                  (when ?current-fn
                    (let [?ignore `ignore#
                          ?args `args#]
                      `(fn [~?ignore & ~?args]
                         (apply ~?current-fn ~?args)))))
        ?clause-map (-> (apply hash-map ?clauses)
                        (update-in ['component-will-update] add-arg)
                        (update-in ['component-did-update] add-arg)
                        (update-in ['should-component-update?] add-arg))
        ?clauses (apply concat ?clause-map)
        ]
    `(reacl.core/class->view
      (reacl.core/class ~?name ~?component ~?app-state ~?local-state [~@?args]
                        ~@?clauses))))

(defmacro defview
  "Define a Reacl view, see [[view]] for documentation.

  The syntax is

      (reacl.core/defview <name> [<this-name> [<local-state-name>]] [<param> ...]
        render <renderer-exp>
        [initial-state <initial-state-exp>]
        [<lifecycle-method-name> <lifecycle-method-exp> ...]
        [handle-message <messager-handler-exp>]

        <event-handler-name> <event-handler-exp> ...)

  This expands to this:

      (def <name>
        (reacl.core/view <name> [<this-name> [<local-state-name>]] [<param> ...]
          render <renderer-exp>
          [initial-state <initial-state-exp>]
          [<lifecycle-method-name> <lifecycle-method-exp> ...]
          [handle-message <messager-handler-exp>]

          <event-handler-name> <event-handler-exp> ...))"
  [?name & ?stuff]
  `(def ~?name (reacl.core/view ~(str ?name) ~@?stuff)))


;; (mixin [<this-name> [<app-state-name> [<local-state-name>]]] [<param> ...])

;; FIXME: should really be restricted to lifecycle methods we know we support

(defmacro mixin
  [& ?stuff]
  (let [[?component ?stuff] (split-symbol ?stuff `component#)
        [?app-state ?stuff] (split-symbol ?stuff `app-state#)
        [?local-state ?stuff] (split-symbol ?stuff `local-state#)
          
        [[& ?args] & ?clauses] ?stuff

        ?clause-map (apply hash-map ?clauses)
        ?wrap (fn [?f]
                (if ?f
                  (let [?more `more#]
                    `(fn [~?component ~?app-state ~?local-state [~@?args] & ~?more]
                       (apply ~?f ~?more)))
                  'nil))
        ?wrapped (into {}
                       (map (fn [[?n ?f]] [(keyword ?n) (?wrap ?f)])
                            ?clause-map))]
    `(reacl.core/create-mixin ~?wrapped)))

