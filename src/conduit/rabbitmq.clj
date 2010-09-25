(ns conduit.rabbitmq
  (:use
     conduit.core)
  (:import
   [com.rabbitmq.client Connection ConnectionFactory Channel
    MessageProperties QueueingConsumer]
   [java.util UUID]))

(declare *channel*)
(declare *exchange*)

(defn declare-queue [queue]
  (.queueDeclare *channel* queue false false false false {})
  (.queueBind *channel* queue *exchange* queue))

(defn purge-queue [queue]
  (.queuePurge *channel* queue))

(defn consumer [queue]
  (let [consumer (QueueingConsumer. *channel*)]
    (.basicConsume *channel* queue, false, consumer)
    consumer))

(defn publish [queue msg]
  (let [msg-str (binding [*print-dup* true]
                  (pr-str msg))]
    (.basicPublish *channel* *exchange* queue
                   (MessageProperties/PERSISTENT_TEXT_PLAIN)
                   (.getBytes msg-str))))

(defn get-msg
  ([queue] (try
            (.nextDelivery (consumer queue))
            (catch InterruptedException e
              nil)))
  ([queue msecs] (.nextDelivery (consumer queue) msecs)))

(defn read-msg [m]
  (read-string (String. (.getBody m))))

(defn ack-message [msg]
  (.basicAck *channel*
             (.getDeliveryTag (.getEnvelope msg))
             false))

(defn rabbitmq-pub-reply [source id]
  (fn rabbitmq-reply [x]
    (let [reply-queue (str (UUID/randomUUID))]
      (.queueDeclare *channel* reply-queue false false false true {})
      (.queueBind *channel* reply-queue *exchange* reply-queue)
      (publish source [id [x reply-queue]])
      (let [msg (get-msg reply-queue)]
        (ack-message msg)
        [(read-msg msg) rabbitmq-reply]))))

(defn rabbitmq-sg-fn [source id]
  (fn rabbitmq-reply [x]
    (let [reply-queue (str (UUID/randomUUID))]
      (.queueDeclare *channel* reply-queue false false false true {})
      (.queueBind *channel* reply-queue *exchange* reply-queue)
      (publish source [id [x reply-queue]])
      (fn []
        (let [msg (get-msg reply-queue)]
          (ack-message msg)
          [(read-msg msg) rabbitmq-reply])))))

(defn rabbitmq-pub-no-reply [source id]
  (fn rabbitmq-no-reply [x]
    (publish source [id x])
    [[] rabbitmq-no-reply]))

(defn reply-fn [f]
  (partial (fn rabbitmq-reply-fn [f [x reply-queue]]
             (let [[new-x new-f] (f x)]
               (publish reply-queue new-x)
               [[] (partial rabbitmq-reply-fn new-f)]))
           f))

(defn a-rabbitmq [source id proc]
  (let [source (str source)
        id (str id)
        reply-id (str id "-reply")]
    (assoc proc
           :type :rabbitmq
           :source source
           :id id
           :reply (rabbitmq-pub-reply source reply-id)
           :no-reply (rabbitmq-pub-no-reply source id)
           :scatter-gather (rabbitmq-sg-fn source reply-id)
           :parts (assoc (:parts proc)
                         source {:type :rabbitmq
                                 id (:no-reply proc)
                                 reply-id (reply-fn (:reply proc))}))))

(defn msg-stream [queue & [msecs]]
  (let [consumer (consumer queue)]
    (if msecs
      (fn this-fn1 [x]
        (let [msg (.nextDelivery consumer msecs)]
          (when msg
            [[msg] this-fn1])))
      (fn this-fn2 [x]
        (try
          (let [msg (.nextDelivery consumer)]
            [[msg] this-fn2])
          (catch InterruptedException e
            nil))))))

(defn rabbitmq-run [p queue channel exchange & [msecs]]
  (binding [*channel* channel
            *exchange* exchange]
    (let [queue (str queue)
          get-next (msg-stream queue msecs)]
      (when-let [handler-map (get-in p [:parts queue])]
        (loop [[[raw-msg] get-next] (get-next nil)]
          (when raw-msg
            (let [[target msg] (read-msg raw-msg)]
              (try
                (let [handler (get handler-map target)
                      new-handler (if handler
                                    (second (handler msg))
                                    handler)]
                  (ack-message raw-msg)
                  new-handler))
              (recur (get-next nil)))))))))
