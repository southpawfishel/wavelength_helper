(ns wavelength-server.core
  (:require [org.httpkit.server :as server
             :refer [with-channel
                     websocket?
                     on-receive
                     on-close
                     run-server
                     send!
                     ]]
            
            [clojure.data.json :as json]
            [ring.util.response :as response]
            ;; [compojure.handler :only [site]]
            [compojure.core :refer [defroutes routes GET POST DELETE ANY context]
             :as compojure]
            [compojure.route :as route])
  
  (:gen-class))

(def connected (atom #{}))

(def server-state (atom {}))

(defn comm-handler [ring-request]
  (let [room (-> ring-request :params :room)]
    ;; unified API for WebSocket and HTTP long polling/streaming
    (with-channel ring-request channel  ; get the channel
      ;; (swap! connected conj channel)
      (swap! server-state update-in [room :connected] (fn [connected]
                                                        (conj (or connected #{})
                                                              channel)))

      ;; send current users
      (doseq [user (-> @server-state
                       (get room)
                       (get :users)
                       vals)]
        (send! channel (json/write-str user)))

      ;; startround
      (send! channel
             (json/write-str (-> @server-state (get room) :round)))

      ;; update score
      (send! channel
             (json/write-str (-> @server-state (get room) :score)))
      
      (on-receive channel (fn [data]
                            (doseq [ch (-> @server-state (get room) :connected )
                                    :when (not= ch channel)]
                              (try
                                (send! ch data)
                                (catch Exception e)))

                            (let [msg (json/read-str data)]
                              (case (get msg "type")
                                "userUpdate"
                                (swap! server-state assoc-in [room :users channel] msg)

                                "updateCards"
                                (swap! server-state assoc-in [room :cards] msg)

                                "startRound"
                                (swap! server-state assoc-in [room :round] msg)

                                "updateScore"
                                (swap! server-state assoc-in [room :score] msg)

                                ;; else
                                nil))
                            ))
      (on-close channel (fn [status]
                          (when-let [uid (-> @server-state (get room) :users (get channel) (get "uid"))]
                            (doseq [ch (-> @server-state (get room) :connected )]
                              (try
                                (send! ch (json/write-str {"type"  "userLeft"
                                                           "uid" uid}))
                                (catch Exception e))))

                          (swap! server-state update-in [room :connected] disj channel)
                          (swap! server-state update-in [room :users] dissoc channel))))))



(def my-routes
  (routes
   (GET "/:room{[a-zA-Z0-9.\\-]+}/ws" [] comm-handler)
   ;; (GET "/index.html" [] (response/resource-response "index.html" {:root "public"}))
   ;; (GET "/" [] (response/resource-response "index.html" {:root "public"}))
   (GET "/" [] "You need to specify a room. Try http://wavelength.smith.rocks/roomName")
   (GET "/:room{[a-zA-Z0-9.\\-]+}" [] (response/resource-response "index.html" {:root "public"}))
   (GET "/bar" [] "Hello Bar")
   (route/not-found "Not Found2"))
  )




(comment
  (def server (run-server #'my-routes {:port 8080}))
  )

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (def server (run-server #'my-routes {:port 8080}))
  )
