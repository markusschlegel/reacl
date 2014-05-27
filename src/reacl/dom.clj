(ns ^{:author "Michael Sperber"
      :doc "Supporting macros for Reacl's DOM library   ."}
  reacl.dom)

(defmacro defdom
  "Internal macro for constructing DOM-construction wrappers."
  [n]
  `(def ~n (dom-function ~(symbol (str "js/React.DOM." (name n))))))

(defmacro letdom
  "Bind DOM nodes to names for use in event handlers.

  This should be used together with `reacl.core/class' or `reacl.core/defclass'.

  Its syntax is like `let', but all right-hand sides must evaluate to
  virtual DOM nodes - typically input elements.

  The objects can be used with the `dom-node' function provided by
` reacl.core/class' or `reacl.core/defclass', which returns the
  corresponding real DOM node.

  Example:

  (reacl.core/defclass search-bar
    app-state [filter-text in-stock-only on-user-input]
    render
    (fn [& {:keys [dom-node]}]
      (dom/letdom
       [textbox (dom/input
                 {:type \"text\"
                  :placeholder \"Search...\"
                  :value filter-text
                  :onChange (fn [e]
                              (on-user-input
                               (.-value (dom-node textbox))
                               (.-checked (dom-node checkbox))))})
        checkbox (dom/input
                  {:type \"checkbox\"
                   :value in-stock-only
                   :onChange (fn [e]
                               (on-user-input
                                (.-value (dom-node textbox))
                                (.-checked (dom-node checkbox))))})]
       (dom/form
        textbox
        (dom/p
         checkbox
         \"Only show products in stock\")))))

  Note that the resulting DOM-node objects need to be used together
  with the other DOM wrappers in reacl.dom."
  [clauses body0 & bodies]
  ;; FIXME: error check
  (let [pairs (partition 2 clauses)]
    `(let [~@(mapcat (fn [p]
                       (let [lhs (first p)]
                         [lhs `(reacl.dom/make-dom-binding ~(str lhs))]))
                     pairs)]
       ~@(map (fn [p]
                (let [lhs (first p)
                      rhs (second p)]
                  `(reacl.dom/set-dom-binding! ~(first p)
                                               ~(second p))))
              pairs)
       ~body0 ~@bodies)))

       
                             
        