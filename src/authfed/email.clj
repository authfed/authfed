(ns authfed.email
 (:require [amazonica.aws.simpleemail :as ses]
           [authfed.config :as config]))

(defn send-message!
 "Try to send message by API call to Amazon SES, throw exception on error."
 [opts]
 (let [{::keys [params endpoint]} config/email]
  (ses/send-email {:endpoint endpoint} (merge params opts))))
