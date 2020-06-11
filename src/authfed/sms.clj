(ns authfed.sms
 (:require [hato.client :as hc]
           [authfed.config :as config]))

(defn send-message!
 "Try to send message by POST request to gateway endpoint, throw exception on error."
 [opts]
 (let [{::keys [params endpoint]} config/sms
       {:keys [status body]} (hc/post endpoint {:query-params (merge params opts)})]
  (if (and (<= 200 status 299)
           (if (= endpoint "https://api.smsbroadcast.com.au/api-adv.php")
               (.startsWith body "OK") true))
   body
   (throw (new Exception (str "failed to send: " body))))))
