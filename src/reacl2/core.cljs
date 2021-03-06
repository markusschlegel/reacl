(ns ^{:doc "Reacl core functionality."}
  reacl2.core
  (:require [cljsjs.react]
            [cljsjs.react.dom]))

;; This is needed to keep track of the current local state of the
;; components as we process an action.  Actions can observe changes to
;; the local state.  However, React processes changes to its
;; state (which contains the local state) in a delayed fashion.

;; So this is a dynamically bound map from component to local state to
;; track the local state in the current cycle.

(def ^:dynamic *local-state-map* {})

;; Ditto for the app state, but read access to this is much more focussed.
(def ^:dynamic *app-state-map* {})

(defn- ^:no-doc local-state-state
  "Set Reacl local state in the given state object.

   For internal use."
  [st local-state]
  (aset st "reacl_local_state" local-state)
  st)

(defn ^:no-doc set-local-state!
  "Set Reacl local state of a component.

   For internal use."
  [this local-state]
  (.setState this (local-state-state #js {} local-state)))

(defn- ^:no-doc state-extract-local-state
  "Extract local state from the state of a Reacl component.

   For internal use."
  [state]
  ; otherweise Closure :advanced screws it up
  (aget state "reacl_local_state"))

(defn ^:no-doc extract-local-state
  "Extract local state from a Reacl component.

   For internal use."
  [this]
  (let [res (get *local-state-map* this ::not-found)]
    (case res
      (::not-found) (state-extract-local-state (.-state this))
      res)))

(defn- ^:no-doc props-extract-initial-app-state
  "Extract initial applications state from props of a Reacl 2toplevel component.

   For internal use."
  [props]
  (aget props "reacl_initial_app_state"))

(defn extract-initial-app-state
  "Extract initial applications state from a Reacl component."
  [this]
  (props-extract-initial-app-state (.-props this)))

(defn- ^:no-doc tl-state-extract-app-state
  "Extract latest applications state from state of a toplevel Reacl component.

   For internal use."
  [state]
  (aget state "reacl_app_state"))

(defn- ^:no-doc props-extract-reaction
  [props]
  (aget props "reacl_reaction"))

(defn- ^:no-doc data-extract-app-state
  "Extract the latest applications state from a Reacl component data.

   For internal use."
  [props state]
  ;; before first render, it's the initial-app-state, afterwards
  ;; it's in the state
  (if (and (not (nil? state))
           (.hasOwnProperty state "reacl_app_state"))
    (tl-state-extract-app-state state)
    (props-extract-initial-app-state props)))

(defn- ^:no-doc extract-app-state
  "Extract the latest applications state from a Reacl component.

   For internal use."
  [this]
  (data-extract-app-state (.-props this) (.-state this)))

(def ^:private extract-current-app-state extract-app-state)

(defn- ^:no-doc app-state-state
  "Set Reacl app state in the given state object.

  For internal use."
  [st app-state]
  (aset st "reacl_app_state" app-state)
  st)

(defn- ^:no-doc props-extract-args
  "Get the component args for a component from its props.

   For internal use."
  [props]
  (aget props "reacl_args"))

(defn ^:no-doc extract-args
  "Get the component args for a component.

   For internal use."
  [this]
  (props-extract-args (.-props this)))

(defn ^:no-doc extract-locals
  "Get the local bindings for a component.

   For internal use."
  [this]
  (let [state (.-state this)
        props (.-props this)]
    (if (and (not (nil? state))
             (.hasOwnProperty state "reacl_locals"))
      (aget state "reacl_locals")
      (aget props "reacl_initial_locals"))))

(defn- ^:no-doc locals-state
  "Set Reacl locals in the given state object.

  For internal use."
  [st locals]
  (aset st "reacl_locals" locals)
  st)
  
(defn ^:no-doc set-locals!
  "Set the local bindings for a component.

  For internal use."
  [this locals]
  (.setState this (locals-state #js {} locals)))

(defn ^:no-doc compute-locals
  "Compute the locals.
  For internal use."
  [clazz app-state args]
  ((aget clazz "__computeLocals") app-state args))

(declare return)

(defn ^:no-doc make-props
  "Forge the props for a React element, for testing.

  For internal use."
  [cl app-state args]
  #js {:reacl_args (vec args)
       :reacl_reduce_action (fn [app-state action]
                              (return :action action))})

(declare react-class)

(defn ^:no-doc make-state
  "Forge the state for a React element, for testing.

  For internal use."
  [cl app-state local-state args]
  (-> #js {}
      (locals-state (compute-locals (react-class cl) app-state args))
      (app-state-state app-state)
      (local-state-state local-state)))

(declare invoke-reaction)

(defrecord ^{:doc "Type for a reaction, a restricted representation for callback."
             :no-doc true}
    Reaction 
    [component make-message args]
  Fn
  IFn
  (-invoke [this value]
    (invoke-reaction component this value)))

(def no-reaction 
  "Use this as a reaction if you don't want to react to an app-state change."
  nil)

(defn pass-through-reaction
  "Use this if you want to pass the app-state as the message.

  `component` must be the component to send the message to"
  [component]
  (assert (not (nil? component)))
  (Reaction. component identity nil))

(defn reaction
  "A reaction that says how to deal with a new app state in a subcomponent.

  - `component` component to send a message to
  - `make-message` function to apply to the new app state and any additional `args`, to make the message.

  Common specialized reactions are [[no-reaction]] and [[pass-through-reaction]]."
  [component make-message & args]
  (assert (not (nil? component)))
  (assert (not (nil? make-message)))
  (Reaction. component make-message args))

(declare send-message! component-parent)

(defn invoke-reaction
  "Invokes the given reaction with the given message value (usually an app-state)."
  [this reaction value]
  (let [target (:component reaction)
        real-target
        (case target
          :parent
          (component-parent this)
          target)]
    (send-message! real-target (apply (:make-message reaction) value (:args reaction)))))

; On app state:
;
; It starts with the argument to the instantiation of toplevel or
; embedded components. We put that app-state into
;
;   props.reacl_initial_app_state
;
; In getInitialState of those top components, we take that over into
; their state, into
;
;  state.reacl_app_state

(defn- ^:no-doc app-state+recompute-locals-state
  "Set Reacl app state and recomputed locals (for the given component) in the given state object.

  For internal use."
  [st this app-state]
  (-> st
      (locals-state (compute-locals (.-constructor this) app-state (extract-args this)))
      (app-state-state app-state)))

(defn ^:no-doc component-parent
  [comp]
  (aget (.-context comp) "reacl_parent"))

(defrecord EmbedAppState
    [app-state ; new app state from child
     embed ; function parent-app-state child-app-state |-> parent-app-state
     ])

(defrecord KeywordEmbedder [keyword]
  Fn
  IFn
  (-invoke [this outer inner]
    (assoc outer keyword inner)))

(defn- ^:no-doc app-state-changed!
  "Invoke the app-state change reaction for the given component.

  For internal use."
  [this app-state]
  (when-let [reaction (props-extract-reaction (.-props this))]
    (assert (instance? Reaction reaction))
    (invoke-reaction this reaction app-state)))

(defn ^:no-doc set-app-state!
  "Set the application state associated with a Reacl component.
   May not be called before the first render.

   For internal use."
  [this app-state]
  (assert (.hasOwnProperty (.-state this) "reacl_app_state"))
  (.setState this (app-state+recompute-locals-state #js {} this app-state))
  (app-state-changed! this app-state))

(defprotocol ^:no-doc IReaclClass
  (-react-class [clazz])
  (-instantiate-toplevel-internal [clazz rst])
  (-compute-locals [clazz app-state args]))

(defn react-class
  "Extract the React class from a Reacl class."
  [clazz]
  (-react-class clazz))

(defn has-class?
  "Find out if an element was generated from a certain Reacl class."
  [clazz element]
  (identical? (.-type element) (react-class clazz)))

(defn ^:no-doc make-local-state
  "Make a React state containing Reacl local variables and local state.

   For internal use."
  [this local-state]
  #js {:reacl_local_state local-state})

(defn ^:no-doc default-should-component-update?
  "Implements [[should-component-update?]] for React.

  For internal use only."
  [this app-state local-state locals args next-app-state next-local-state & next-args]
  (or (not= app-state
            next-app-state)
      (not= local-state
            next-local-state)
      (not= args
            (vec next-args))))

(defrecord ^{:doc "Optional arguments for instantiation."}
    Options
    [map])

(defn opt
  "Create options for component instantiation.

  Takes keyword arguments `:reaction`, `:embed-app-state`,
  `:reduce-action`.

  - `:reaction` must be a reaction to an app-state change, typically created via
    [[reaction]], [[no-reaction]], or [[pass-through-reaction]].  -
  - `:embed-app-state` can be specified as an alternative to `:reaction`
    and specifies, that the app state of this component is embedded in the
    parent component's app state.  This must be a function of two arguments, the
    parent app state and this component's app-state.  It must return a new parent
    app state.
  - `:reduce-action` takes arguments `[app-state action]` where `app-state` is the app state
    of the component being instantiated, and `action` is an action.  This
    should call [[return]] to handle the action.  By default, it is a function
    with body `(return :action action)` returning the action unchanged.
    This is called on every action generated by the child component.
    Local-state changes through this function are ignored."
  [& {:as mp}]
  {:pre [(every? (fn [[k _]]
                   (contains? #{:reaction :embed-app-state :reduce-action} k))
                 mp)]}
  (Options. mp))

(defn- ^:no-doc deconstruct-opt
  [rst]
  (if (empty? rst)
    [{} rst]
    (let [frst (first rst)]
      (if (instance? Options frst)
        [(:map frst) (rest rst)]
        [{} rst]))))

(defn- ^:no-doc deconstruct-opt+app-state
  [has-app-state? rst]
  (let [[opts rst] (deconstruct-opt rst)
        [app-state args] (if has-app-state?
                           [(first rst) (rest rst)]
                           [nil rst])]
    [opts app-state args]))

(defn ^:no-doc instantiate-toplevel-internal
  "Internal function to instantiate a Reacl component.

  - `clazz` is the Reacl class
  - `opts` is an object created with [[opt]]
  - `app-state` is the application state
  - `args` is a seq of class arguments"
  {:arglists '([clazz opts app-state & args]
               [clazz app-state & args]
               [clazz opts & args]
               [clazz & args])}
  [clazz has-app-state? rst]
  (let [[opts app-state args] (deconstruct-opt+app-state has-app-state? rst)]
    (js/React.createElement (react-class clazz)
                            #js {:reacl_initial_app_state app-state
                                 :reacl_initial_locals (-compute-locals clazz app-state args)
                                 :reacl_args (vec args)
                                 :reacl_reaction (or (:reaction opts) ; FIXME: what if we have both?
                                                     (if-let [embed-app-state (:embed-app-state opts)]
                                                       (reaction :parent ->EmbedAppState embed-app-state)
                                                       no-reaction))
                                 :reacl_reduce_action (or (:reduce-action opts)
                                                          (fn [app-state action] ; FIXME: can probably greatly optimize this case
                                                            (return :action action)))
                                 :reacl_handle_action (fn [this action] nil)}))) ; we're done

(defn instantiate-toplevel
  "For testing purposes mostly."
  {:arglists '([clazz opts app-state & args]
               [clazz app-state & args]
               [clazz opts & args]
               [clazz & args])}
  [clazz frst & rst]
  (instantiate-toplevel-internal clazz true (cons frst rst)))

(defn- ^:no-doc action-reducer
  [this]
  (aget (.-props this) "reacl_reduce_action"))

(declare reduce-and-handle-action!)

(defn ^:no-doc instantiate-embedded-internal
  "Internal function to instantiate an embedded Reacl component.

  - `clazz` is the Reacl class
  - `opts` is an object created with [[opt]]
  - `app-state` is the application state
  - `args` is a seq of class arguments"
  {:arglists '([clazz opts app-state & args]
               [clazz app-state & args]
               [clazz opts & args]
               [& args])}
  [clazz has-app-state? rst]
  (let [[opts app-state args] (deconstruct-opt+app-state has-app-state? rst)]
    (js/React.createElement (react-class clazz)
                            #js {:reacl_initial_app_state app-state
                                 :reacl_initial_locals (-compute-locals clazz app-state args)
                                 :reacl_args args
                                 :reacl_reaction (or (:reaction opts) ; FIXME: what if we have both?
                                                     (if-let [embed-app-state (:embed-app-state opts)]
                                                       (reaction :parent ->EmbedAppState embed-app-state)
                                                       no-reaction))
                                 :reacl_reduce_action (or (:reduce-action opts)
                                                          (fn [app-state action] ; FIXME: can probably greatly optimize this case
                                                            (return :action action)))
                                 ;; propagate the action upwards
                                 :reacl_handle_action (fn [this action] ; this is an action already reduced by this component
                                                        (reduce-and-handle-action! (component-parent this) action))})))

(defn ^:no-doc instantiate-embedded-internal-v1
  [clazz app-state reaction args]
  (js/React.createElement (react-class clazz)
                          #js {:reacl_initial_app_state app-state
                               :reacl_initial_locals (-compute-locals clazz app-state args)
                               :reacl_args args
                               :reacl_reaction reaction
                               :reacl_reduce_action (fn [app-state action]
                                                      (return :action action))
                               :reacl_handle_action (fn [this action]
                                                      (let [parent (aget (.-context this) "reacl_parent")
                                                            parent-handle (aget (.-props parent) "reacl_handle_action")]
                                                        (parent-handle parent action)))}))

(defn render-component
  "Instantiate and render a component into the DOM.

  - `element` is the DOM element
  - `clazz` is the Reacl class
  - `opts` is an object created with [[opt]]
  - `app-state` is the application state
  - `args` is a seq of class arguments"
  {:arglists '([element clazz opts app-state & args]
               [element clazz app-state & args]
               [element clazz opts & args]
               [element clazz & args])}
  [element clazz & rst]
  (js/ReactDOM.render
   (-instantiate-toplevel-internal clazz rst)
   element))

(defrecord ^{:doc "Type of a unique value to distinguish nil from no change of state.
            For internal use in [[reacl.core/return]] and [[reacl.core/set-state!]]."
             :private true
             :no-doc true} 
    KeepState
  [])

(def ^{:doc "Single value of type KeepState.
             Can be used in reacl.core/return to indicate no (application or local)
             state change, which is different from setting it to nil."
       :no-doc true}
  keep-state (KeepState.))

(defn ^:no-doc keep-state?
  "Check if an object is the keep-state object."
  [x]
  (instance? KeepState x))

(defrecord ^{:doc "Composite object for the effects caused by [[return]].
  For internal use."
             :private true
             :no-doc true}
    Returned
    [app-state local-state actions])

(defrecord ^{:doc "This marks effects returned by an invocation.

  The `args` field contains the list of arguments to [[return]].

  Used internally by [[return]]."}
    Effects
    [args])

(defn return
  "Return state from a Reacl event handler.

   Has two optional keyword arguments:

   - `:app-state` is for a new app state.
   - `:local-state` is for a new component-local state.
   - `:action` is for an action

   A state can be set to nil. To keep a state unchanged, do not specify
  that option, or specify the value [[reacl.core/keep-state]]."
  [& args]
  (Effects. args))

(defn ^:no-doc action-effects->returned
  [^Effects efs]
  (loop [args (seq (:args efs))
         app-state keep-state
         local-state keep-state
         actions (transient [])]
    (if (empty? args)
      (Returned. app-state local-state (persistent! actions))
      (let [arg (second args)
            nxt (nnext args)]
        (case (first args)
          (:app-state) (recur nxt arg local-state actions)
          (:local-state) (recur nxt app-state local-state actions)
          (:action) (recur nxt app-state local-state (conj! actions arg))
          (throw (str "invalid argument " (first args) " to reacl/return")))))))

(defn- ^:no-doc action-effect
  [reduce-action app-state action]
  (if-let [^Effects efs (reduce-action app-state action)]
    (let [ret (action-effects->returned efs)
          app-state-2 (:app-state ret)]
      [(:app-state ret) (:actions ret)])
    [keep-state [action]]))

(defn ^:no-doc effects->returned
  "Reduce the effects of a [[return]] invocation, returning a `Returned` object.

  For internal use only."
  [this ^Effects efs]
  (let [reduce-action (action-reducer this)
        actual-app-state (extract-app-state this)]
    (loop [args (seq (:args efs))
           app-state keep-state
           local-state keep-state
           actions (transient [])]
      (if (empty? args)
        (Returned. app-state local-state (persistent! actions))
        (let [arg (second args)
              nxt (nnext args)]
          (case (first args)
            (:app-state) (recur nxt arg local-state actions)
            (:local-state) (recur nxt app-state arg actions)
            (:action) (let [[action-app-state new-actions] (action-effect reduce-action
                                                                   (if (keep-state? app-state)
                                                                     actual-app-state
                                                                     app-state)
                                                                   arg)]
                        (recur nxt
                               (if (instance? KeepState action-app-state)
                                 app-state
                                 action-app-state)
                               local-state
                               (reduce conj! actions new-actions)))
            (throw (str "invalid argument " (first args) " to reacl/return"))))))))

(defn- ^:no-doc set-state!
  "Set the app state and component state according to what return returned.

  Note that the actual update of `(.-state component)` might be deferred and thus not
  visible immediately."
  [component as ls cont]
  (when-not (and (= keep-state ls)
                 (= keep-state as))
    ;; avoid unnecessary re-rendering
    (.setState component
               (cond-> #js {}
                 (not= keep-state ls) (local-state-state ls)
                 (not= keep-state as) (app-state+recompute-locals-state component as))))
  (when (not= keep-state as)
    (app-state-changed! component as))
  (binding [*app-state-map* (assoc *app-state-map* component as)
            *local-state-map* (assoc *local-state-map* component
                                                       (if (= keep-state ls)
                                                         (extract-local-state component)
                                                         ls))]
    (cont)))

(defn- ^:no-doc handle-message
  "Handle a message for a Reacl component.

  For internal use.

  This returns a `Returned` object."
  [comp msg]
  (if (instance? EmbedAppState msg)
    (Returned. ((:embed msg) (extract-current-app-state comp) (:app-state msg))
               keep-state
               [])
    (let [^Effects efs ((aget comp "__handleMessage") msg)]
      (effects->returned comp efs))))

(defn ^:no-doc handle-message->state
  "Handle a message for a Reacl component.

  For internal use.

  This returns application state and local state."
  [comp msg]
  (let [ret (handle-message comp msg)]
    [(if (not= keep-state (:app-state ret)) (:app-state ret) (extract-app-state comp))
     (if (not= keep-state (:local-state ret)) (:local-state ret) (extract-local-state comp))]))

(defn ^:no-doc handle-actions!
  "Handle actions returned by message handler."
  [comp actions]
  (let [handle (aget (.-props comp) "reacl_handle_action")]
    (doseq [a actions]
      (handle comp a))))

(defn- ^:no-doc reduce-and-handle-action!
  [this action]
  ;; "setState() does not always immediately update the component. It
  ;; may batch or defer the update until later. This makes reading
  ;; this.state right after calling setState() a potential pitfall."

  (let [[app-state actions]
        (action-effect (action-reducer this) (get *app-state-map* this) action)]
    (set-state! this app-state keep-state
                #(handle-actions! this actions))))

(defn handle-returned!
  "Handle all effects described in a [[Returned]] object."
  [comp ^Returned ret]
  (set-state! comp (:app-state ret) (:local-state ret)
              #(handle-actions! comp (:actions ret))))

(defn ^:no-doc handle-effects!
  "Handle all effects described in a [[Effects]] object."
  [comp ^Effects efs]
  (handle-returned! comp (effects->returned comp efs)))

 
(defn send-message!
  "Send a message to a Reacl component.

  Returns the `Returned` object returned by the message handler."
  [comp msg]
  (let [^Returned ret (handle-message comp msg)]
    (handle-returned! comp ret)
    ret))

(defn dispatch-action!
  "Dispatch an action from a Reacl component.

  You can call this from a regular event handler."
  [comp action]
  (reduce-and-handle-action! comp action))

(defn opt-handle-effects! [component v]
  (when v
    (assert (instance? Effects v))
    (handle-effects! component v)))

;; Attention: duplicate definition for macro in core.clj
(def ^:private specials #{:render :initial-state :handle-message
                          :component-will-mount :component-did-mount
                          :component-will-receive-args
                          :should-component-update?
                          :component-will-update :component-did-update
                          :component-will-unmount
                          :mixins})

(defn- ^:no-doc is-special-fn? [[n f]]
  (not (nil? (specials n))))


;; FIXME: just pass all the lifecycle etc. as separate arguments

(defn create-class [display-name compat-v1? mixins has-app-state? compute-locals fns]
  ;; split special functions and miscs
  (let [{specials true misc false} (group-by is-special-fn? fns)
        {:keys [render
                initial-state
                handle-message
                component-will-mount
                component-did-mount
                component-will-receive-args
                should-component-update?
                component-will-update
                component-did-update
                component-will-unmount
                should-component-update?]
         :or {should-component-update? default-should-component-update?}
         }
        (into {} specials)
        ]
    ;; Note that it's args is not & args down there
    (let [;; with locals, but without local-state
          nlocal
          (fn [f]
            (and f
                 (fn [& react-args]
                   (this-as this
                     (apply f this (extract-app-state this) (extract-locals this) (extract-args this)
                            react-args)))))
          ;; also with local-state (most reacl methods)
          std
          (fn [f]
            (and f
                 (fn [& react-args]
                   (this-as this
                     (apply f this (extract-app-state this) (extract-local-state this)
                            (extract-locals this) (extract-args this)
                            react-args)))))
          std-current
          (fn [f]
            (and f
                 (fn [& react-args]
                   (this-as this
                     (apply f this (extract-current-app-state this) (extract-local-state this)
                            (extract-locals this) (extract-args this)
                            react-args)))))
          std+state
          (fn [f]
            (and f
                 (fn [& react-args]
                   (this-as this
                     (opt-handle-effects! this (apply f
                                                       this
                                                       (extract-app-state this) (extract-local-state this)
                                                       (extract-locals this) (extract-args this)
                                                       react-args))))))
          with-args
          (fn [f]
            (and f
                 (fn [other-props]
                   (this-as this
                     (apply f this (extract-app-state this) (extract-local-state this) (extract-locals this) (extract-args this) (props-extract-args other-props))))))
          ;; and one arg with next/prev-state
          with-state-and-args
          (fn [f]
            (and f
                 (fn [other-props other-state]
                   (this-as this
                     (apply f this (extract-app-state this) (extract-local-state this) (extract-locals this) (extract-args this)
                            (data-extract-app-state other-props other-state)
                            (state-extract-local-state other-state)
                            (props-extract-args other-props))))))

          react-method-map
          (merge
           (into {} (map (fn [[n f]] [n (std f)]) misc))
           {"displayName"
            display-name

            "getInitialState"
            (nlocal (fn [this app-state locals args]
                      (let [local-state (when initial-state
                                          (initial-state this app-state locals args))
                            state (make-local-state this local-state)]
                        ;; app-state will be the initial_app_state here
                        (aset state "reacl_app_state" app-state)
                        state)))

            "mixins"
            (when mixins
              (object-array mixins))

            "render"
            (std render)

            ;; Note handle-message must always see the most recent
            ;; app-state, even if the component was not updated after
            ;; a change to it.
            "__handleMessage"
            (std-current handle-message)

            "componentWillMount"
            (std+state component-will-mount)

            "getChildContext" (fn []
                                (this-as this 
                                  #js {:reacl_parent this}))

            "componentDidMount"
            (std+state component-did-mount)

            "componentWillReceiveProps"
            (let [f (with-args component-will-receive-args)]
              ;; this might also be called when the args have not
              ;; changed (prevent that?)
              (fn [next-props]
                (this-as this
                  ;; embedded/toplevel has been
                  ;; 'reinstantiated', so take over new
                  ;; initial app-state
                  (let [app-state (props-extract-initial-app-state next-props)]
                    (.setState this #js {:reacl_app_state app-state})
                    (aset this "reacl_current_app_state" app-state)
                    (when f
                      ;; must preserve 'this' here via .call!
                      (opt-handle-effects! this (.call f this next-props)))))))

            "shouldComponentUpdate"
            (let [f (with-state-and-args should-component-update?)]
              (fn [next-props next-state]
                ;; have to check the reaction for embedded
                ;; components for changes - though this will mostly
                ;; force an update!
                (this-as this
                         (or (and (let [callback-now (props-extract-reaction (.-props this))
                                        callback-next (props-extract-reaction next-props)]
                                    (and (or callback-now callback-next)
                                         (not= callback-now callback-next))))
                             (if f
                               (.call f this next-props next-state)
                               true)))))

            "componentWillUpdate"
            (with-state-and-args component-will-update)

            "componentDidUpdate"
            (with-state-and-args component-did-update) ;; here it's
            ;; the previous state&args

            "componentWillUnmount"
            (std component-will-unmount)

            "statics"
            (js-obj "__computeLocals"
                    compute-locals ;; [app-state & args]
                    )
            }
           )

          react-class (js/React.createClass
                       (apply js-obj (apply concat
                                            (filter #(not (nil? (second %)))
                                                    react-method-map))))
          ]
      (aset react-class "childContextTypes" #js {:reacl_parent js/React.PropTypes.object})
      (aset react-class "contextTypes" #js {:reacl_parent js/React.PropTypes.object})
      (if compat-v1?
        (reify
          IFn ; only this is different between v1 and v2
          ;; this + 20 regular args, then rest, so a1..a18
          (-invoke [this app-state reaction]
            (instantiate-embedded-internal-v1 this app-state reaction []))
          (-invoke [this app-state reaction a1]
            (instantiate-embedded-internal-v1 this app-state reaction [a1]))
          (-invoke [this app-state reaction a1 a2]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2]))
          (-invoke [this app-state reaction a1 a2 a3]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3]))
          (-invoke [this app-state reaction a1 a2 a3 a4]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5 a6]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6 a7]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5 a6 a7]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6 a7 a8]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5 a6 a7 a8]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6 a7 a8 a9]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5 a6 a7 a8 a9]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6 a7 a8 a9 a10]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18]
            (instantiate-embedded-internal-v1 this app-state reaction [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18]))
          (-invoke [this app-state reaction a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 rest]
            (instantiate-embedded-internal-v1 this app-state reaction (concat [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18] rest)))
          IReaclClass
          (-instantiate-toplevel-internal [this rst]
            (instantiate-toplevel-internal this has-app-state? rst))
          (-compute-locals [this app-state args]
            (compute-locals app-state args))
          (-react-class [this] react-class))
        (reify
          IFn
          (-invoke [this]
            (instantiate-embedded-internal this has-app-state? []))
          (-invoke [this a1]
            (instantiate-embedded-internal this has-app-state? [a1]))
          (-invoke [this a1 a2]
            (instantiate-embedded-internal this has-app-state? [a1 a2]))
          (-invoke [this a1 a2 a3]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3]))
          (-invoke [this a1 a2 a3 a4]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4]))
          (-invoke [this a1 a2 a3 a4 a5]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5]))
          (-invoke [this a1 a2 a3 a4 a5 a6]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7 a8]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7 a8 a9]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20]
            (instantiate-embedded-internal this has-app-state? [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20]))
          (-invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 rest]
            (instantiate-embedded-internal this has-app-state? (concat [a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20] rest)))
          IReaclClass
          (-instantiate-toplevel-internal [this rst]
            (instantiate-toplevel-internal this has-app-state? rst))
          (-compute-locals [this app-state args]
            (compute-locals app-state args))
          (-react-class [this] react-class))))))

(def ^:private mixin-methods #{:component-will-mount :component-did-mount
                               :component-will-update :component-did-update
                               :component-will-receive-args
                               :component-will-unmount})

(defn ^:no-doc create-mixin
  [fn-map]
  (let [entry (fn [tag name post]
                (if-let [f (get fn-map tag)]
                  [name
                   (fn [arg-fns]
                     (fn []
                       (this-as this
                                (post
                                 this
                                 (f this (extract-app-state this) (extract-local-state this)
                                    (map (fn [arg-fn] (arg-fn this)) arg-fns))))))]
                  nil))
        app+local-entry (fn [tag name]
                          (if-let [f (get fn-map tag)]
                            [name
                             (fn [arg-fns]
                               (fn [other-props other-state]
                                 (this-as this
                                          (f this (extract-app-state this) (extract-local-state this)
                                             (map (fn [arg-fn] (arg-fn this)) arg-fns)
                                             (data-extract-app-state other-props other-state)
                                             (state-extract-local-state other-state)))))]
                            nil))
        pass-through (fn [this res] res)
        entries (filter identity
                        (vector 
                         (entry :component-did-mount "componentDidMount" (fn [this res] (opt-handle-effects! this res)))
                         (entry :component-will-mount "componentWillMount" (fn [this res] (opt-handle-effects! this res)))
                         (entry :component-will-unmount "componentWillUnmount" pass-through)
                         (app+local-entry :component-will-update "componentWillUpdate")
                         (app+local-entry :component-did-update "componentDidUpdate")
                         ;; FIXME: :component-will-receive-args 
                         ))]
    (fn [& arg-fns]
      (apply js-obj 
             (apply concat
                    (map (fn [[name fnfn]]
                           [name (fnfn arg-fns)])
                         entries))))))

