(ns onyx.messaging.protocols.status-publisher)

(defprotocol PStatusPublisher
  (start [this])
  (stop [this])
  (info [this])
  (set-session-id! [this session-id])
  (get-session-id [this])
  (set-heartbeat! [this])
  (get-heartbeat [this])
  (block! [this])
  (unblock! [this])
  (blocked? [this])
  (set-completed! [this completed?])
  (completed? [this])
  (new-replica-version! [this])
  (offer-heartbeat! [this replica-version epoch]) 
  (offer-ready-reply! [this replica-version epoch]) 
  (offer-barrier-status! [this replica-version epoch]))