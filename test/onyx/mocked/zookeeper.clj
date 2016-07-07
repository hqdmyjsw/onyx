(ns onyx.mocked.zookeeper
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :refer [info error warn fatal]]
            [onyx.log.replica]
            [onyx.extensions :as extensions]))

(defrecord FakeZooKeeper [config]
  component/Lifecycle
  (start [component] component)
  (stop [component] component))

(defn fake-zookeeper [entries store checkpoints config]
  (map->FakeZooKeeper {:entries entries
                       :store store
                       :checkpoints checkpoints
                       :entry-num 0
                       :config config}))

(defmethod extensions/write-log-entry FakeZooKeeper
  [log data]
  (swap! (:entries log) 
         (fn [entries] 
           (conj (vec entries)
                 (assoc data :message-id (count entries)))))
  log)

(defmethod extensions/read-log-entry FakeZooKeeper
  [{:keys [entries]} n]
  (get @entries n))

(defmethod extensions/register-pulse FakeZooKeeper
  [& all])

(defmethod extensions/on-delete FakeZooKeeper
  [& all])

(defmethod extensions/group-exists? FakeZooKeeper
  [& all]
  ;; Always show true - we will always manually leave
  true)

(defmethod extensions/subscribe-to-log FakeZooKeeper
  [log & _]
  (onyx.log.replica/starting-replica (:config log)))

(defmethod extensions/write-chunk :default
  [log kw chunk id]
  (cond 
   (= :task kw)
   (swap! (:store log) assoc [kw id (:id chunk)] chunk)
   (= :exception kw)
   (do (println "Task Exception:" chunk)
       (throw chunk))
   :else
   (swap! (:store log) assoc [kw id] chunk))
  log)

(defmethod extensions/read-chunk :default
  [log kw id & rst]
  (if (= :task kw)
    (get @(:store log) [kw id (first rst)])
    (get @(:store log) [kw id])))

(defmethod extensions/write-checkpoint FakeZooKeeper
  [log job-id replica-version epoch task-id slot-id checkpoint]
  (swap! (:checkpoints log)
         assoc-in 
         [job-id [replica-version epoch] [task-id slot-id]]
         checkpoint))

(defmethod extensions/read-checkpoints FakeZooKeeper
  [log job-id] 
  (get @(:checkpoints log) job-id))
