(ns ^{:author "Michael Sperber"
      :doc "Reacl core functionality."}
  reacl.core)

(defn- ^:no-doc jsmap
  "Convert a Clojure map to a JavaScript hashmap."
  [clmp]
  (loop [clmp clmp
         args []]
    (if (not (seq clmp))
      (apply js-obj args)
      (recur (nnext clmp)
             (concat args [(name (first clmp)) (second clmp)])))))

(defn ^:no-doc set-local-state!
  "Set Reacl local state of a component.

   For internal use."
  [this local-state]
  (.setState this #js {:reacl_local_state local-state}))

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
  (state-extract-local-state (.-state this)))

(defn ^:no-doc extract-toplevel
  "Extract toplevel component of a Reacl component.

   For internal use."
  [this]
  ((aget (.-props this) "reacl_get_toplevel")))

(defn ^:no-doc props-extract-app-state-atom
  "Extract the applications state atom from props of a Reacl component.

   For internal use."
  [props]
  (aget props "reacl_app_state_atom"))

(defn ^:no-doc props-extract-app-state
  "Extract applications state from props of a Reacl component.

   For internal use."
  [props]
  @(props-extract-app-state-atom props))

(defn ^:no-doc extract-app-state
  "Extract applications state from a Reacl component.

   For internal use."
  [this]
  (props-extract-app-state (.-props this)))

(defn ^:no-doc props-extract-args
  "Get the component args for a component from its props.

   For internal use."
  [props]
  (aget props "reacl_args"))

(defn ^:no-doc extract-args
  "Get the component args for a component.

   For internal use."
  [this]
  (props-extract-args (.-props this)))

; The locals are difficult: We want them to be like props on the one
; hand, but they should have new bindings when the app state changes.

; The latter means we cannot generally put them in the component
; state, as the component state survives app-state changes.

; So we need to put them in the props and make them mutable, by
; sticking an atom in the props.

; For updating the locals, we use a two-pronged strategy:

; - For non-top-level, non-embedded components there's no problem, as
;   the components get re-instantiated as a matter of the usual routine.
; - For top-level and embedded components, we reset the atom to update
;   the locals.

(defn ^:no-doc extract-locals
  "Get the local bindings for a component.

   For internal use."
  [this]
  @(aget (.-props this) "reacl_locals"))

(defn ^:no-doc compute-locals
  "Compute the locals.
  For internal use."
  [clazz app-state args]
  (apply (aget clazz "__computeLocals") app-state args))

(defn ^:no-doc set-app-state!
  "Set the application state associated with a Reacl component.

   For internal use."
  [this app-state]
  (let [toplevel (extract-toplevel this)
        toplevel-props (.-props toplevel)
        app-state-atom (aget toplevel-props "reacl_app_state_atom")]
    (reset! app-state-atom app-state)
    (when (identical? this toplevel)
      (reset! (aget toplevel-props "reacl_locals") 
              (compute-locals (.-constructor this) app-state (extract-args this))))
    ;; remember current app-state in toplevel/embedded, for
    ;; implementations of should-component-update?:
    (.setState toplevel #js {:reacl_app_state app-state})
    (if-let [callback (aget toplevel-props "reacl_app_state_callback")]
      (callback app-state))))

(defprotocol ^:no-doc IReaclClass
  (-instantiate [clazz component args])
  (-instantiate-toplevel [clazz app-state args])
  (-instantiate-embedded [clazz component app-state app-state-callback args])
  (-react-class [clazz]))

(defn react-class
  "Extract the React class from a Reacl class."
  [clazz]
  (-react-class clazz))

(defn ^:no-doc make-local-state
  "Make a React state containing Reacl local variables and local state.

   For internal use."
  [this local-state]
  #js {:reacl_local_state local-state})

(declare toplevel? embedded?)

(defn ^:no-doc default-should-component-update?
  "Implements [[shouldComponentUpdate]] for React.

  For internal use only."
  [this app-state local-state locals args next-props next-state]
  (let [state (.-state this)
        props (.-props this)]
    ;; at the toplevel/embedded level, we look at the app-state; if
    ;; that changes, the whole tree must update; if not, then react
    ;; will call this for the children, which can therefor presume the
    ;; app-state has not changed.
    (or (and (or (toplevel? this) (embedded? this))
             (or
              ;; app-state was not set before, now it's set
              (and (not (.hasOwnProperty state "reacl_app_state"))
                   (.hasOwnProperty next-state "reacl_app_state"))
              ;; app-state changed
              (not= (aget state "reacl_app_state") (aget next-state "reacl_app_state"))))
        ;; this was added for the case of an updated app-state argument
        ;; for an embed call (compares the atoms, not the values)
        (and (embedded? this)
             (not= (props-extract-app-state-atom props)
                   (props-extract-app-state-atom next-props)))
        ;; args or local-state changed?
        (not= (extract-args this)
              (props-extract-args next-props))
        (not= (extract-local-state this)
              (state-extract-local-state next-state))
        ;; uncomment for debugging:
        (comment do (println "think props is unchanged:")
            (println props)
            (println next-props)
            (println "think state is unchanged:")
            (println state)
            (println next-state)
            false))))

(defn ^:no-doc instantiate-internal
  "Internal function to instantiate a Reacl component.

  - `clazz` is the React class (not the Reacl class ...).
  - `parent` is the component from which the Reacl component is instantiated.
  - `args` are the arguments to the component.
  - `locals` are the local variables of the components."
  [clazz parent args locals]
  (let [props (.-props parent)]
    (clazz #js {:reacl_get_toplevel (aget props "reacl_get_toplevel")
                :reacl_app_state_atom (aget props "reacl_app_state_atom")
                :reacl_embedded_ref_count (atom nil)
                :reacl_args args
                :reacl_locals (atom locals)})))

(defn ^:no-doc instantiate-toplevel-internal
  "Internal function to instantiate a Reacl component.

  - `clazz` is the React class (not the Reacl class ...).
  - `app-state` is the  application state.
  - `args` are the arguments to the component.
  - `locals` are the local variables of the components."
  [clazz app-state args locals]
  (let [toplevel-atom (atom nil)] ;; NB: set by render-component
    (clazz #js {:reacl_toplevel_atom toplevel-atom
                :reacl_get_toplevel (fn [] @toplevel-atom)
                :reacl_embedded_ref_count (atom nil)
                :reacl_app_state_atom (atom app-state)
                :reacl_args args
                :reacl_locals (atom locals)})))

(defn- ^:no-doc toplevel?
  "Is this component toplevel?"
  [this]
  (aget (.-props this) "reacl_toplevel_atom"))

(defn ^:no-doc instantiate-embedded-internal
  "Internal function to instantiate an embedded Reacl component.

  `clazz' is the React class (not the Reacl class ...).
  `parent' is the component from which the Reacl component is instantiated.
  `app-state' is the  application state.
  `app-state-callback' is a function called with a new app state on changes.
  `args' are the arguments to the component.
  `locals' are the local variables of the components."
  [clazz parent app-state app-state-callback args locals]
  (let [toplevel-atom (atom nil)
        ;; React will replace whatever is returned by (clazz ...) on mounting.
        ;; This is the only way to get at the mounted component, it seems.

        ;; That's not all, though: The ref-count atom will sometimes
        ;; be from the old object from the previous render cycle; it's
        ;; initialized in the render method, off the class macro.
        ref-count (aget (.-props parent) "reacl_embedded_ref_count")
        ref (str "__reacl_embedded__" @ref-count)]
    (swap! ref-count inc)
    (clazz #js {:reacl_get_toplevel (fn [] (aget (.-refs parent) ref))
                :reacl_app_state_atom (atom app-state)
                :reacl_embedded_ref_count (atom nil)
                :reacl_args args
                :reacl_locals (atom locals)
                :reacl_app_state_callback app-state-callback
                :ref ref})))

(defn- ^:no-doc embedded?
  "Check if a Reacl component is embedded."
  [comp]
  (some? (aget (.-props comp ) "reacl_app_state_callback")))

(defn instantiate-toplevel
  "Instantiate a Reacl component at the top level.

  - `clazz` is the Reacl class.
  - `app-state` is the application state
  - `args` are the arguments to the component."
  [clazz app-state & args]
  (-instantiate-toplevel clazz app-state args))

(defn render-component
  "Instantiate and render a component into the DOM.

  - `element` is the DOM element
  - `clazz` is the Reacl clazz
  - `app-state` is the application state
  - `args` are the arguments of the component."
  [element clazz app-state & args]
  (let [instance
        (js/React.renderComponent
         (apply instantiate-toplevel clazz app-state args)
         element)]
    (reset! (aget (.-props instance) "reacl_toplevel_atom") instance)
    instance))

(defn embed
  "Embed a Reacl component.

  This creates a component with its own application state that can be
  embedded in a surrounding application.  Any changes to the app state 
  lead to the callback being invoked.

  - `clazz` is the Reacl class.
  - `parent` is the component from which the Reacl component is instantiated.
  - `app-state` is the application state.
  - `app-state-callback` is a function called with a new app state on changes.
  - `args` are the arguments to the component."
  [clazz parent app-state app-state-callback & args]
  (-instantiate-embedded clazz parent app-state app-state-callback args))

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

(defrecord ^{:doc "Composite object for app state and local state.
            For internal use in reacl.core/return."
             :private true
             :no-doc true}
    State
    [app-state local-state])

(defn return
  "Return state from a Reacl event handler.

   Has two optional keyword arguments:

   - `:app-state` is for a new app state.
   - `:local-state` is for a new component-local state.

   A state can be set to nil. To keep a state unchanged, do not specify
   that option, or specify the value [[reacl.core/keep-state]]."
  [& args]
  (let [args-map (apply hash-map args)
        app-state (if (contains? args-map :app-state)
                    (get args-map :app-state)
                    keep-state)
        local-state (if (contains? args-map :local-state)
                      (get args-map :local-state)
                      keep-state)]
    (State. app-state local-state)))

(defn set-state!
  "Set the app state and component state according to what return returned."
  [component ps]
  (if (not (= keep-state (:local-state ps)))
    (set-local-state! component (:local-state ps)))
  (if (not (= keep-state (:app-state ps)))
    (set-app-state! component (:app-state ps))))

(defn event-handler
  "Create a Reacl event handler from a function.

   `f` must be a function that returns a return value created by
   [[reacl.core/return]], with a new application state and/or component-local
   state.

   [[reacl.core/event-handler]] turns that function `f` into an event
   handler, that is, a function that sets the new application state
   and/or component-local state.  `f` gets passed any argument the event
   handler gets passed, plus, as its last argument, the component-local
   state.

   This example event handler can be used with onSubmit, for example:

       (reacl/event-handler
        (fn [e _ text]
          (.preventDefault e)
          (reacl/return :app-state (concat todos [{:text text :done? false}])
                        :local-state \"\")))

   Note that `text` is the component-local state."
  [f]
  (fn [component & args]
    (let [local-state (extract-local-state component)
          ps (apply f (concat args [local-state]))]
      (set-state! component ps))))

(defn- ^:no-doc handle-message
  "Handle a message for a Reacl component.

  For internal use.

  This returns a State object."
  [comp msg]
  ((aget comp "__handleMessage") msg))

(defn ^:no-doc handle-message->state
  "Handle a message for a Reacl component.

  For internal use.

  This returns application state and local state."
  [comp msg]
  (let [ps (handle-message comp msg)]
    [(or (:app-state ps) (extract-app-state comp))
     (or (:local-state ps) (extract-local-state comp))]))

(defn send-message!
  "Send a message to a Reacl component."
  [comp msg]
  (let [st (handle-message comp msg)]
    (set-state! comp st)))

;; Attention: duplicate definition for macro in core.clj
(def ^:private specials #{:render :initial-state :handle-message
                          :component-will-mount :component-did-mount
                          :component-will-receive-props
                          :should-component-update?
                          :component-will-update :component-did-update
                          :component-will-unmount})
(defn- ^:no-doc is-special-fn? [[n f]]
  (not (nil? (specials n))))

(defn- ^:no-doc reset-embedded-ref-count! [this]
  (reset! (aget (.-props this) "reacl_embedded_ref_count") 0))

(defn create-class [display-name compute-locals fns]
  ;; split special functions and miscs
  (let [{specials true misc false} (group-by is-special-fn? fns)
        {:keys [render
                initial-state
                handle-message
                component-will-mount
                component-did-mount
                component-will-receive-props
                should-component-update?
                component-will-update
                component-did-update
                component-will-unmount]
         :or {:should-component-update? default-should-component-update?}
         }
        (into {} specials)
        ]
    ;; Note that it's args is not & args down there
    (let [ ;; base: prepend this app-state and args
          base
          (fn [f]
            (when f
              (fn [& react-args]
                (this-as this
                         (apply f this (extract-app-state this) (extract-args this)
                                react-args)))))
          ;; with locals, but without local-state
          nlocal
          (fn [f]
            (base (when f
                    (fn [this app-state args & react-args]
                      (apply f this app-state (extract-locals this) args
                                               react-args)))))
          ;; also with local-state (most reacl methods)
          std
          (fn [f]
            (nlocal (when f
                      (fn [this app-state locals args & react-args]
                        ;;(if (not (.-state this)) (throw (pr-str this)))
                        (apply f this app-state (extract-local-state this)
                               locals args
                               react-args)))))
          ;; and one arg with next/prev-props
          other-props
          (fn [f]
            (std (when f
                   (fn [this app-state local-state locals args & [other-props]]
                     (apply f this app-state local-state locals args
                            ;; FIXME: this is not the
                            ;; next-app-state/prev-app-state, is it?
                            (props-extract-app-state other-props)
                            (props-extract-args other-props))))))
          ;; and one arg with next/prev-state
          other-props-and-state
          (fn [f]
            (other-props (when f
                           (fn [this app-state local-state locals args other-app-state other-args & [other-state]]
                             (apply f this app-state local-state locals args
                                    other-app-state
                                    (state-extract-local-state other-state)
                                    other-args)))))

          ;; with next-app-state and next-args
          next1 other-props
          ;; with next-app-state, next-local-state and next-args
          next2 other-props-and-state
          ;; with prev-app-state, prev-local-state and prev-args
          prev2 other-props-and-state

          react-method-map
          (merge
           (into {} (map (fn [[n f]] [n (std f)]) misc))
           {"displayName"
            display-name

            "getInitialState"
            (nlocal (if initial-state
                      (fn [this app-state locals args]
                        (make-local-state this
                                          (initial-state app-state locals args)))
                      (fn [this] (make-local-state this nil))
                      ))

            "render"
            (std (when render
                   (fn [this app-state local-state locals args]
                     (reset-embedded-ref-count! this)
                     (render this app-state local-state locals args))))

            "__handleMessage"
            (std handle-message)

            "componentWillMount"
            (std component-will-mount)

            "componentDidMount"
            (std component-did-mount)

            "componentWillReceiveProps"
            (std component-will-receive-props) ;; -> next1

            "shouldComponentUpdate"
            (std should-component-update?) ;; -> next2

            "componentWillUpdate"
            (std component-will-update) ;; -> next2

            "componentDidUpdate"
            (std component-did-update) ;; -> prev2

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
      (reify
        IFn
        (-invoke [this component & args]
          (-instantiate this component args))
        IReaclClass
        (-instantiate [this component args]
          (instantiate-internal react-class component
                                args (compute-locals (extract-app-state component)
                                                     args)))
        (-instantiate-toplevel [this app-state args]
          (instantiate-toplevel-internal react-class app-state args
                                         (compute-locals app-state args)))
        (-instantiate-embedded [this component app-state app-state-callback args]
          (instantiate-embedded-internal react-class component app-state
                                         app-state-callback args
                                         (compute-locals app-state args)))
        (-react-class [this] react-class)
        ))))
