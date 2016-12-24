(ns onyx.messaging.aeron.subscriber
  (:require [onyx.messaging.protocols.subscriber :as sub]
            [onyx.messaging.protocols.status-publisher :as status-pub]
            [onyx.messaging.aeron.status-publisher :refer [new-status-publisher]]
            [onyx.messaging.common :as common]
            [onyx.messaging.aeron.utils :as autil :refer [action->kw stream-id heartbeat-stream-id]]
            [onyx.compression.nippy :refer [messaging-compress messaging-decompress]]
            [onyx.static.default-vals :refer [arg-or-default]]
            [onyx.types]
            [taoensso.timbre :refer [debug info warn] :as timbre])
  (:import [java.util.concurrent.atomic AtomicLong]
           [org.agrona.concurrent UnsafeBuffer]
           [org.agrona ErrorHandler]
           [io.aeron Aeron Aeron$Context Publication Subscription Image ControlledFragmentAssembler UnavailableImageHandler AvailableImageHandler]
           [io.aeron.logbuffer ControlledFragmentHandler ControlledFragmentHandler$Action]))

;; FIXME to be tuned
(def fragment-limit-receiver 10000)

(defn ^java.util.concurrent.atomic.AtomicLong lookup-ticket [ticket-counters replica-version short-id session-id]
  (-> ticket-counters
      (swap! update-in 
             [replica-version short-id session-id]
             (fn [ticket]
               (or ticket (AtomicLong. -1))))
      (get-in [replica-version short-id session-id])))

(defn assert-epoch-correct! [epoch message-epoch message]
  (when-not (= (inc epoch) message-epoch)
    (throw (ex-info "Unexpected barrier found. Possibly a misaligned subscription."
                    {:message message
                     :epoch epoch}))))

(defn invalid-replica-found! [replica-version message]
  (throw (ex-info "Shouldn't have received a message for this replica-version as we have not sent a ready message." 
                  {:replica-version replica-version 
                   :message message})))

(defn unavailable-image [sub-info lost-sessions]
  (reify UnavailableImageHandler
    (onUnavailableImage [this image] 
      (swap! lost-sessions conj (.sessionId image))
      (println "Lost sessions now" @lost-sessions)
      (info "UNAVAILABLE image " (.position image) " " (.sessionId image) " " sub-info))))

(defn available-image [sub-info]
  (reify AvailableImageHandler
    (onAvailableImage [this image] 
      (info "AVAILABLE image " (.position image) " " (.sessionId image) " " sub-info))))

(deftype Subscriber 
  [peer-id ticket-counters peer-config dst-task-id slot-id site batch-size
   liveness-timeout channel ^Aeron conn ^Subscription subscription 
   lost-sessions 
   ^:unsynchronized-mutable sources
   ^:unsynchronized-mutable short-id-status-pub
   ^:unsynchronized-mutable status-pubs
   ^:unsynchronized-mutable ^ControlledFragmentAssembler assembler 
   ^:unsynchronized-mutable replica-version 
   ^:unsynchronized-mutable epoch
   ^:unsynchronized-mutable recovered
   ^:unsynchronized-mutable recover 
   ;; Going to need to be ticket per source, session-id per source, etc
   ;^:unsynchronized-mutable ^AtomicLong ticket 
   ^:unsynchronized-mutable batch]
  sub/Subscriber
  (start [this]
    (let [error-handler (reify ErrorHandler
                          (onError [this x] 
                            (println "Aeron messaging subscriber error" x)
                            ;(System/exit 1)
                            ;; FIXME: Reboot peer
                            (taoensso.timbre/warn x "Aeron messaging subscriber error")))
          media-driver-dir (:onyx.messaging.aeron/media-driver-dir peer-config)
          sinfo [dst-task-id slot-id site]
          lost-sessions (atom #{})
          ctx (cond-> (Aeron$Context.)
                error-handler (.errorHandler error-handler)
                media-driver-dir (.aeronDirectoryName ^String media-driver-dir)
                true (.availableImageHandler (available-image sinfo))
                true (.unavailableImageHandler (unavailable-image sinfo lost-sessions)))
          conn (Aeron/connect ctx)
          liveness-timeout (arg-or-default :onyx.peer/publisher-liveness-timeout-ms peer-config)
          channel (autil/channel peer-config)
          stream-id (stream-id dst-task-id slot-id site)
          sub (.addSubscription conn channel stream-id)]
      (sub/add-assembler 
       (Subscriber. peer-id ticket-counters peer-config dst-task-id
                    slot-id site batch-size liveness-timeout channel conn sub lost-sessions 
                    [] {} {} nil nil nil nil nil nil)))) 
  (stop [this]
    (info "Stopping subscriber" [dst-task-id slot-id site])
    ;; Possible issue here when closing. Should hard exit? Or should just safely close more
    ;; Can trigger this with really short timeouts
    ;; ^[[1;31mio.aeron.exceptions.RegistrationException^[[m: ^[[3mUnknown subscription link: 78^[[m
    ;; ^[[1;31mjava.util.concurrent.ExecutionException^[[m: ^[[3mio.aeron.exceptions.RegistrationException: Unknown subscription link: 78^[[m
    (when subscription 
      (try
       (.close subscription)
       (catch io.aeron.exceptions.RegistrationException re
         (info "Error stopping subscriber's subscription." re))))
    (when conn (.close conn))
    (run! status-pub/stop (vals status-pubs))
    (Subscriber. peer-id ticket-counters peer-config dst-task-id slot-id site
                 batch-size nil nil nil nil nil nil nil nil nil nil nil nil nil nil)) 
  (add-assembler [this]
    (set! assembler (ControlledFragmentAssembler. this))
    this)
  (info [this]
    {:subscription {:rv replica-version
                    :e epoch
                    :sources sources 
                    :dst-task-id dst-task-id 
                    :slot-id slot-id 
                    :blocked? (sub/blocked? this)
                    :site site
                    :channel (autil/channel peer-config)
                    :channel-id (.channel subscription)
                    :registation-id (.registrationId subscription)
                    :stream-id (.streamId subscription)
                    :closed? (.isClosed subscription)
                    :images (mapv autil/image->map (.images subscription))}
     :status-pubs (into {} (map (fn [[k v]] [k (status-pub/info v)]) status-pubs))})
  (timed-out-publishers [this]
    (let [curr-time (System/currentTimeMillis)] 
      (->> status-pubs
           (filter (fn [[peer-id spub]] 
                     (< (+ (status-pub/get-heartbeat spub)
                           liveness-timeout)
                        curr-time)))
           (map key))))
  ; (alive? [this]
  ;   (cond (.isClosed subscription)
  ;         :closed
  ;         (< (+ (apply min 
  ;                      (map status-pub/get-heartbeat 
  ;                           (vals status-pubs)))
  ;               liveness-timeout)
  ;            (System/currentTimeMillis))
  ;         :no-heartbeat
  ;         (let [status-pub-session-ids (set (keep status-pub/get-session-id (vals status-pubs)))
  ;               lost-valid (clojure.set/intersection @lost-sessions status-pub-session-ids)]
  ;           (not (empty? lost-valid))) 
  ;         :interrupted-session
  ;         :else
  ;         :alive))
  (equiv-meta [this sub-info]
    (and (= dst-task-id (:dst-task-id sub-info))
         (= slot-id (:slot-id sub-info))
         (= site (:site sub-info))))
  (set-epoch! [this new-epoch]
    (set! epoch new-epoch)
    this)
  (set-replica-version! [this new-replica-version]
    (run! status-pub/new-replica-version! (vals status-pubs))
    (set! replica-version new-replica-version)
    (set! recovered false)
    (set! recover nil)
    this)
  (recovered? [this]
    recovered)
  (get-recover [this]
    recover)
  (set-recover! [this recover*]
    (when-not (= recover* recover) 
      (throw (ex-info "Two different subscribers sent differing recovery information"
                      {:recover1 recover
                       :recover2 recover*
                       :replica-version replica-version
                       :epoch epoch})))
    (set! recover recover*)
    (set! recovered true)
    this)
  (unblock! [this]
    (run! status-pub/unblock! (vals status-pubs))
    this)
  (blocked? [this]
    (not (some (complement status-pub/blocked?) (vals status-pubs))))
  (completed? [this]
    (not (some (complement status-pub/completed?) (vals status-pubs))))
  (poll! [this]
    (debug "Before poll on channel" (sub/info this))
    (set! batch (transient []))
    (let [rcv (.controlledPoll ^Subscription subscription ^ControlledFragmentHandler assembler fragment-limit-receiver)]
      (debug "After poll" (sub/info this))
      (persistent! batch)))
  (offer-heartbeat! [this]
    (run! #(status-pub/offer-barrier-status! % replica-version epoch) (vals status-pubs)))
  (offer-barrier-status! [this peer-id]
    (let [status-pub (get status-pubs peer-id)
          ret (status-pub/offer-barrier-status! status-pub replica-version epoch)] 
      (debug "Offer barrier status:" replica-version epoch ret)
      ret))
  (src-peers [this]
    (keys status-pubs))
  (update-sources! [this sources*]
    (let [prev-peer-ids (set (keys status-pubs))
          next-peer-ids (set (map :src-peer-id sources*))
          peer-id->site (into {} (map (juxt :src-peer-id :site) sources*))
          rm-peer-ids (clojure.set/difference prev-peer-ids next-peer-ids)
          add-peer-ids (clojure.set/difference next-peer-ids prev-peer-ids)
          removed (reduce (fn [spubs src-peer-id]
                            (status-pub/stop (get spubs src-peer-id))
                            (dissoc spubs src-peer-id))
                          status-pubs
                          rm-peer-ids)
          final (reduce (fn [spubs src-peer-id]
                          (let [site (get peer-id->site src-peer-id)]
                            (assoc spubs 
                                   src-peer-id
                                   (->> (new-status-publisher peer-config peer-id src-peer-id site)
                                        (status-pub/start)))))
                        removed
                        add-peer-ids)
          short-id->status-pub (->> sources*
                                    (map (fn [{:keys [short-id src-peer-id]}]
                                           [short-id (get final src-peer-id)]))
                                    (into {}))]
      (run! (fn [[short-id spub]] 
              (status-pub/set-short-id! spub short-id)) 
            short-id->status-pub)
      (set! short-id-status-pub short-id->status-pub)
      (set! status-pubs final)
      (set! sources sources*))
    this)
  ControlledFragmentHandler
  (onFragment [this buffer offset length header]
    (let [ba (byte-array length)
          _ (.getBytes ^UnsafeBuffer buffer offset ba)
          message (messaging-decompress ba)
          rv-msg (:replica-version message)
          ret (if (< rv-msg replica-version)
                ControlledFragmentHandler$Action/CONTINUE
                (if (> rv-msg replica-version)
                  (do
                   ;; update heartbeat since we're blocked and it's not upstream's fault
                   (-> (short-id-status-pub (:short-id message))
                       (status-pub/set-heartbeat!))
                   ControlledFragmentHandler$Action/ABORT)
                  (if-let [spub (get short-id-status-pub (:short-id message))]
                    (case (:type message)
                      0 (if (>= (count batch) batch-size) ;; full batch, get out
                          ControlledFragmentHandler$Action/ABORT
                          (let [_ (assert (pos? epoch))
                                _ (status-pub/set-heartbeat! spub)
                                session-id (.sessionId header)
                                ;; TODO: slow
                                ticket (lookup-ticket ticket-counters replica-version 
                                                      (:short-id message) session-id)
                                ticket-val ^long (.get ticket)
                                position (.position header)
                                got-ticket? (and (< ticket-val position)
                                                 (.compareAndSet ticket ticket-val position))]
                            (when got-ticket? (reduce conj! batch (:payload message)))
                            ControlledFragmentHandler$Action/CONTINUE))
                      1 (if (zero? (count batch))
                          (let [short-id (:short-id message)]
                            (assert-epoch-correct! epoch (:epoch message) message)
                            ;; For use determining whether job is complete. Refactor later
                            (status-pub/set-heartbeat! spub)
                            (status-pub/block! spub)
                            (when (:completed? message) 
                              (status-pub/set-completed! spub (:completed? message)))
                            (when (contains? message :recover)
                              (sub/set-recover! this (:recover message)))
                            ControlledFragmentHandler$Action/BREAK)
                          ControlledFragmentHandler$Action/ABORT)
                      2 (do (status-pub/set-heartbeat! spub) 
                            ControlledFragmentHandler$Action/CONTINUE)
                      3 (do (-> spub
                                (status-pub/set-heartbeat!)
                                (status-pub/set-session-id! (.sessionId header))
                                (status-pub/offer-ready-reply! replica-version epoch))
                            ControlledFragmentHandler$Action/CONTINUE)
                      (throw (ex-info "Handler should never be here." {:replica-version replica-version
                                                                       :epoch epoch
                                                                       :message message})))
                    ControlledFragmentHandler$Action/CONTINUE)))]
      (debug [:read-subscriber (action->kw ret) channel dst-task-id] (into {} message))
      ret)))

(defn new-subscription [peer-config peer-id ticket-counters sub-info]
  (let [{:keys [dst-task-id slot-id site batch-size]} sub-info]
    (->Subscriber peer-id ticket-counters peer-config dst-task-id slot-id site batch-size
                  nil nil nil nil nil nil nil nil nil nil nil nil nil nil)))
