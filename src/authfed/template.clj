(ns authfed.template)

(def title
 {:tag "title" :content "Authfed"})

(def stylesheet
 {:tag "link"
  :attrs {:rel "stylesheet"
		        :href "https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0/css/bootstrap.min.css"
		        :integrity "sha384-Gn5384xqQ1aoWXA+058RXPxPg6fy4IWvTNh0E263XmFcJlSAwiGgFAW/dAiS6JXm"
		        :crossorigin "anonymous"}})

(defn html
 ([] (html nil))
 ([body]
		{:tag "html"
		 :content [{:tag "head"
		            :content [title stylesheet]}
		           {:tag "body"
		            :content [{:tag "div"
                         :attrs {:class "container"
                                 :style "margin-top: 40px;"}
                         :content body}]}]}))
