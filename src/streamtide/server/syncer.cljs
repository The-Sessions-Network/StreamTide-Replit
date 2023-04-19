(ns streamtide.server.syncer
  "Populates the DB from the events coming from the blockchain"
  (:require
    [bignumber.core :as bn]
    [camel-snake-kebab.core :as camel-snake-kebab]
    [cljs-web3-next.eth :as web3-eth]
    [cljs-web3-next.core :as web3-core]
    [cljs.core.async :as async :refer [<! go]]
    [cljs.core.async.impl.protocols :refer [ReadPort]]
    [district.shared.async-helpers :refer [<? safe-go]]
    [district.server.smart-contracts :as smart-contracts]
    [district.server.web3-events :as web3-events]
    [district.time :as time]
    [district.server.config :refer [config]]
    [district.server.web3 :refer [ping-start ping-stop web3]]
    [mount.core :as mount :refer [defstate]]
    [streamtide.server.db :as db]
    [streamtide.shared.utils :as shared-utils]
    [taoensso.timbre :as log]))


(declare start)
(declare stop)

(defonce reload-timeout (atom nil))
(defonce last-block-number (atom -1))

(defstate ^{:on-reload :noop} syncer
          :start (start (merge (:syncer @config)
                               (:syncer (mount/args))))
          :stop (stop syncer))

(defn- get-event [{:keys [:event :log-index :block-number]
                   {:keys [:contract-key :forwards-to]} :contract}]
  {:event/contract-key (name (or forwards-to contract-key))
   :event/event-name (name event)
   :event/log-index log-index
   :event/block-number block-number})

(defn- block-timestamp* [block-number]
  (let [out-ch (async/promise-chan)]
    (smart-contracts/wait-for-block block-number (fn [error result]
                                                   (if error
                                                     (async/put! out-ch error)
                                                     (let [{:keys [:timestamp]} (js->clj result :keywordize-keys true)]
                                                       (log/debug "cache miss for block-timestamp" {:block-number block-number
                                                                                                    :timestamp timestamp})
                                                       (async/put! out-ch timestamp)))))
    out-ch))


;;;;;;;;;;;;;;;;;;;;
;; Event handlers ;;
;;;;;;;;;;;;;;;;;;;;

(defn admin-added-event [_ {:keys [:args]}]
  (let [{:keys [:_admin]} args]
    (safe-go
      (db/upsert-user-info! {:user/address _admin})
      (db/add-role _admin :role/admin))))

(defn admin-removed-event [_ {:keys [:args]}]
  (let [{:keys [:_admin]} args]
    (safe-go
      (db/remove-role _admin :role/admin))))

(defn blacklisted-added-event [_ {:keys [:args]}]
  )

(defn blacklisted-removed-event [_ {:keys [:args]}]
  )

(defn patron-added-event [_ {:keys [:args]}]
  )

(defn round-started-event [_ {:keys [:args]}]
  )

(defn matching-pool-donation-event [_ {:keys [:args]}]
  )

(defn distribute-event [_ {:keys [:args]}]
  )

(defn donate-event [_ {:keys [:args]}]
  )

(defn failed-distribute-event [_ {:keys [:args]}]
  )


;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; End of events handlers ::
;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(def block-timestamp
  (memoize block-timestamp*))

(defn- dispatcher [callback]
  (fn [err {:keys [:block-number] :as event}]
    (if-not event ; event is false when websocket is disconnected
      (log/debug (str "Skipping event. error:" err))
      (safe-go
        (try
          (swap! last-block-number #(max %1 block-number))
          (let [block-timestamp (<? (block-timestamp block-number))
                event (-> event
                          (update :event camel-snake-kebab/->kebab-case)
                          (update-in [:args :version] bn/number)
                          (update-in [:args :timestamp] (fn [timestamp]
                                                          (if timestamp
                                                            (bn/number timestamp)
                                                            block-timestamp))))
                {:keys [:event/contract-key :event/event-name :event/block-number :event/log-index]} (get-event event)
                {:keys [:event/last-block-number :event/last-log-index :event/count]
                 :or {last-block-number -1
                      last-log-index -1
                      count 0}} (db/get-last-event contract-key event-name)
                evt {:event/contract-key contract-key
                     :event/event-name event-name
                     :event/count count
                     :last-block-number last-block-number
                     :last-log-index last-log-index
                     :block-number block-number
                     :log-index log-index}]
            (log/debug "Handling new event" evt)
            (if (or (> block-number last-block-number)
                    (and (= block-number last-block-number) (> log-index last-log-index)))
              (let [result (callback err event)]
                ;; block if we need
                (when (satisfies? ReadPort result)
                  (<! result))
                (log/info "Handled new event" evt)
                (db/upsert-event! {:event/last-log-index log-index
                                   :event/last-block-number block-number
                                   :event/count (inc count)
                                   :event/event-name event-name
                                   :event/contract-key contract-key}))

              (log/info "Skipping handling of a persisted event" evt)))
          (catch js/Error error
            (log/error "Exception when handling event" {:error error
                                                        :event event})
            ;; So we crash as fast as we can and don't drag errors that are harder to debug
            (js/process.exit 1)))))))

(defn- reload-handler [interval]
  (js/setInterval
    (fn []
      (go
        (let [connected? (true? (<! (web3-eth/is-listening? @web3)))]
          (when connected?
            (do
              (log/debug (str "disconnecting from provider to force reload. Last block: " @last-block-number))
              (web3-core/disconnect @web3))))))
    interval))

(defn reload-timeout-start [{:keys [:reload-interval]}]
  (reset! reload-timeout (reload-handler reload-interval)))

(defn reload-timeout-stop []
  (js/clearInterval @reload-timeout))

(defn start [opts]
  (safe-go
    (when-not (:disabled? opts)
      (when-not (web3-eth/is-listening? @web3)
        (throw (js/Error. "Can't connect to Ethereum node")))
      (when-not (= ::db/started @db/streamtide-db)
        (throw (js/Error. "Database module has not started")))
      (let [start-time (shared-utils/now)
            event-callbacks {:streamtide/admin-added-event admin-added-event
                             :streamtide/admin-removed-event admin-removed-event
                             :streamtide/blacklisted-added-event blacklisted-added-event
                             :streamtide/blacklisted-removed-event blacklisted-removed-event
                             :streamtide/patron-added-event patron-added-event
                             :streamtide/round-started-event round-started-event
                             :streamtide/matching-pool-donation-event matching-pool-donation-event
                             :streamtide/distribute-event distribute-event
                             :streamtide/donate-event donate-event
                             :streamtide/failed-distribute-event failed-distribute-event}
            callback-ids (doall (for [[event-key callback] event-callbacks]
                                  (web3-events/register-callback! event-key (dispatcher callback))))]
        (web3-events/register-after-past-events-dispatched-callback! (fn []
                                                                       (log/warn "Syncing past events finished" (time/time-units (- (shared-utils/now) start-time)) ::start)
                                                                       (ping-start {:ping-interval 10000})
                                                                       (when (> (:reload-interval opts) 0)
                                                                         (reload-timeout-start (select-keys opts [:reload-interval])))))
        (assoc opts :callback-ids callback-ids)))))

(defn stop [syncer]
  (reload-timeout-stop)
  (ping-stop)
  (web3-events/unregister-callbacks! (:callback-ids @syncer)))
