(ns cells.cell
  (:require [re-db.d :as d]
            [maria.eval :as e]
            [maria.blocks.blocks]
            [goog.net.XhrIo :as xhr]
            [goog.net.ErrorCode :as errors]
            [maria.show :as show]
            [re-view.core :as v]
            [com.stuartsierra.dependency :as dep])
  (:require-macros [cells.cell :refer [defcell cell cell-fn]])
  (:import [goog Uri]))

(def ^:dynamic *cell-stack* (list))
(def ^:dynamic *compute-dependents* true)

(defonce dep-graph (volatile! (dep/graph)))

(defn dependencies [cell] (dep/immediate-dependencies @dep-graph cell))
(defn dependents [cell] (dep/immediate-dependents @dep-graph cell))

(defprotocol IReactiveCompute
  (-set-function! [this f])

  (-compute-dependents! [this])
  (-compute! [this] "Compute a new value")
  (-compute-and-listen! [this] "Compute a value, registering pattern listeners"))

(defprotocol IAsync
  (-set-async-state! [this value] [this value message] "Set loading status")
  (status [this])
  (message [this] "Read message associated with async state"))

(defn- query-string [query]
  (-> Uri .-QueryData (.createFromMap (clj->js query)) (.toString)))

(deftype Cell [id ^:mutable f ^:mutable state eval-context]

  INamed
  (-name [this] id)

  IAsync
  (-set-async-state! [this value] (-set-async-state! this value nil))
  (-set-async-state! [this value message]
    (set! state (assoc state
                  :async/status value
                  :async/message message))
    (-compute-dependents! this))
  (status [this]
    @this
    (:async/status state))
  (message [this]
    @this
    (:async/message state))

  IDeref
  (-deref [this]
    (when-let [other-cell (first *cell-stack*)]
      (when-not (= other-cell this)
        (vswap! dep-graph dep/depend other-cell this)))
    (d/get ::cells id))

  IReset
  (-reset! [this newval]
    (d/transact! [[:db/add ::cells id newval]])
    (-compute-dependents! this)
    newval)

  ISwap
  (-swap! [this f] (reset! this (f @this)))
  (-swap! [this f a] (reset! this (f @this a)))
  (-swap! [this f a b] (reset! this (f @this a b)))
  (-swap! [this f a b xs] (reset! this (apply f @this a b xs)))

  e/IDispose
  (on-dispose [this f]
    (vswap! e/-dispose-callbacks update id conj f))
  (-dispose! [this]
    (doseq [f (get @e/-dispose-callbacks id)]
      (f))
    (vswap! e/-dispose-callbacks dissoc id)
    this)

  IReactiveCompute
  (-compute-dependents! [this]
    (when *compute-dependents*
      (binding [*compute-dependents* false]
        (doseq [cell (dep/transitive-dependents @dep-graph this)]
          (-compute! cell)))))

  (-set-function! [this newf]
    (set! f newf))

  (-compute! [this]
    (when-not (contains? (set *cell-stack*) this)
      (vswap! dep-graph dep/remove-node this)
      (binding [*cell-stack* (cons this *cell-stack*)
                e/*eval-context* eval-context]
        (try
          (-reset! this (f this))
          (catch js/Error e
            (e/dispose! this)
            (throw e))))))
  (-compute-and-listen! [this]
    (e/on-dispose eval-context #(e/dispose! this))
    (-compute! this)
    this)

  show/IShow
  (show [this] @this))


(def -cells (volatile! {}))

(defn make-cell
  ([f]
   (make-cell (d/unique-id) f))
  ([id f]
   (if-let [cell (get @-cells id)]
     (do (when-not (contains? (set (map name *cell-stack*)) id)
           (e/-dispose! cell)
           (-reset! cell nil))
         (-set-function! cell f)
         (-compute-and-listen! cell))

     (let [cell (->Cell id f {:dep-> #{}
                              :->dep #{}} e/*eval-context*)]
       (-compute-and-listen! cell)
       (vswap! -cells assoc id cell)
       cell))))

(defn interval [n f]
  (let [the-cell (first *cell-stack*)
        the-interval (volatile! nil)]
    (vreset! the-interval (js/setInterval #(binding [*cell-stack* (cons the-cell *cell-stack*)]
                                             (try
                                               (-reset! the-cell (f @the-cell))
                                               (catch js/Error e
                                                 (js/clearInterval @the-interval)
                                                 (throw e)))) n))
    (e/on-dispose the-cell #(js/clearInterval @the-interval))
    (f @the-cell)))

(defn fetch
  "Fetch a resource from a url. By default, response is parsed as JSON and converted to Clojure via clj->js with :keywordize-keys true.
  Accepts options :parse, an alternate function which will be passed the response text, and :query, a map which will be
  appended to url as a query parameter string."
  ([url]
   (fetch url {} nil))
  ([url options] (fetch url options nil))
  ([url {:keys [parse query]
         :or   {parse (comp #(js->clj % :keywordize-keys true) js/JSON.parse)}} f]
   (let [the-cell (first *cell-stack*)
         the-block e/*eval-context*
         url (cond-> url
                     query (str "?" (query-string query)))]
     (-set-async-state! the-cell :loading)
     (xhr/send url (cell-fn [event]
                            (let [xhrio (.-target event)]
                              (if-not (.isSuccess xhrio)
                                (-set-async-state! the-cell :error {:message (-> xhrio .getLastErrorCode (errors/getDebugMessage))
                                                                    :xhrio   xhrio})
                                (let [formatted-value (try (-> xhrio (.getResponseText) (parse))
                                                           (catch js/Error error
                                                             (e/handle-block-error (:id the-block) error)))]
                                  (do (-set-async-state! the-cell nil)
                                      (reset! the-cell (cond->> formatted-value
                                                                f (f @the-cell)))))))))
     (or @the-cell nil))))

(defn geo-location
  []
  (js/navigator.geolocation.getCurrentPosition
    (cell-fn [location]
             (->> {:latitude  (.. location -coords -latitude)
                   :longitude (.. location -coords -longitude)}
                  (reset! (first *cell-stack*))))))

(comment (defcell a 1)
         (defcell b @a)
         @b
         (defcell c @b)
         @c
         (defcell d @c)
         @d
         (defcell -b @b)
         @-b
         (defcell -c @c)
         @-c

         (doseq [cell [a b c d -b -c]]
           (println {:name  (name cell)
                     :dep-> (map name (dependencies cell))
                     :->dep (map name (dependents cell))
                     :topo  (map name (dep/transitive-dependents @dep-graph cell))})))