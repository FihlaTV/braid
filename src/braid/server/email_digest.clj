(ns braid.server.email-digest
  (:require
    [clojure.string :as string]
    [clj-time.core :as time]
    [clj-time.format :as format]
    [clj-time.coerce :refer [to-date-time]]
    [clojurewerkz.quartzite.jobs :as j :refer [defjob]]
    [clojurewerkz.quartzite.schedule.cron :as cron]
    [clojurewerkz.quartzite.scheduler :as qs]
    [clojurewerkz.quartzite.triggers :as t]
    [clostache.parser :refer [render-resource]]
    [inliner.core :refer [inline-css]]
    [mount.core :refer [defstate]]
    [org.httpkit.client :as http]
    [taoensso.timbre :as timbre]
    [braid.server.conf :refer [config]]
    [braid.server.db.thread :as thread]
    [braid.server.db.user :as user]
    [braid.server.message-format :refer [parse-tags-and-mentions]]
    [braid.server.scheduler :refer [scheduler]]))

; finding data

(defn thread-unseen?
  "A thread is unseen if it's last-open-at is prior to any of the messages'
  created-at"
  [thread]
  (let [last-open (to-date-time (thread :last-open-at))]
    (some (fn [{:keys [created-at]}]
            (time/before? last-open (to-date-time created-at)))
          (thread :messages))))

(defn last-message-after?
  [cutoff thread]
  (some (fn [{:keys [created-at]}]
          (time/after? (to-date-time created-at) cutoff))
        (thread :messages)))

(defn updates-for-user-since
  [user-id cutoff]
  (let [users (user/users-for-user user-id)
        id->nick (into {} (map (juxt :id :nickname)) users)
        id->avatar (into {} (map (juxt :id :avatar)) users)
        pretty-time (comp
                      (partial format/unparse (format/formatter "h:mm MMM d"))
                      to-date-time)]
    (into ()
          (comp
            (filter thread-unseen?)
            (filter (partial last-message-after? cutoff))
            (map
              (fn [t]
                (let [thread-last-open
                      (to-date-time
                        (thread/thread-last-open-at t user-id))]
                  (update t :messages
                          (partial map
                                   (fn [{sender-id :user-id :as m}]
                                     (-> m
                                         (update :content (partial parse-tags-and-mentions user-id))
                                         (assoc :unseen
                                           (if (time/before?
                                                 (to-date-time (m :created-at))
                                                 thread-last-open)
                                             "seen" "unseen"))
                                         (update :created-at pretty-time)
                                         (assoc :sender (id->nick sender-id))
                                         (assoc :sender-avatar
                                           (id->avatar sender-id))))))))))
          (thread/open-threads-for-user user-id))))

(defn daily-update-users
  "Find all ids for users that want daily digest updates"
  []
  (user/user-search-preferences :email-frequency :daily))

(defn weekly-update-users
  "Find all ids for users that want weekly digest updates"
  []
  (user/user-search-preferences :email-frequency :weekly))

; build a message from a thread

; TODO: look into rendering clojurescript to string instead of making a template
(defn create-message
  [threads]
  {:html  (inline-css (render-resource "templates/email_digest.html.mustache"
                                       {:threads threads}))
   :text (render-resource "templates/email_digest.txt.mustache"
                          {:threads threads})})

; sending

(defn send-message
  [to {:keys [text html subject]
       :or {subject "While you were away"}}]
  (http/post (str "https://api.mailgun.net/v3/" (config :mailgun-domain) "/messages")
             {:basic-auth ["api" (config :mailgun-password)]
              :form-params {:to to
                            :from (str "noreply@" (config :mailgun-domain))
                            :subject subject
                            :text text
                            :html html}}))

;; Scheduling

; daily digest
(defjob DailyDigestJob
  [ctx]
  (timbre/debugf "Starting daily email job")
  (let [user-ids (daily-update-users)
        cutoff (time/minus (time/now) (time/days 1))]
    (doseq [uid user-ids]
      (when-let [threads (seq (updates-for-user-since uid cutoff))]
        (let [email (user/user-email uid)]
          (send-message email (create-message threads)))))))

(defn daily-digest-job
  []
  (j/build
    (j/of-type DailyDigestJob)
    (j/with-identity (j/key "jobs.daily-email.1"))))

(defn daily-digest-trigger
  []
  (t/build
    (t/with-identity (t/key "triggers.daily-email"))
    (t/start-now)
    (t/with-schedule
      (cron/schedule
        (cron/daily-at-hour-and-minute 3 0)))))

; weekly digest
(defjob WeeklyDigestJob
  [ctx]
  (timbre/debugf "Starting weekly email job")
  (let [user-ids (weekly-update-users)
        cutoff (time/minus (time/now) (time/days 7))]
    (doseq [uid user-ids]
      (when-let [threads (seq (updates-for-user-since uid cutoff))]
        (let [email (user/user-email uid)]
          (send-message email (create-message threads)))))))

(defn weekly-digest-job
  []
  (j/build
    (j/of-type WeeklyDigestJob)
    (j/with-identity (j/key "jobs.weekly-email.1"))))

(defn weekly-digest-trigger
  []
  (t/build
    (t/with-identity (t/key "triggers.weekly-email"))
    (t/start-now)
    (t/with-schedule
      (cron/schedule
        (cron/weekly-on-day-and-hour-and-minute 1 2 30)))))

(defn add-jobs
  [scheduler]
  (timbre/debugf "scheduling email digest jobs")
  (qs/schedule scheduler (daily-digest-job) (daily-digest-trigger))
  (qs/schedule scheduler (weekly-digest-job) (weekly-digest-trigger)))

(defn remove-jobs
  [scheduler]
  (qs/delete-jobs scheduler [(j/key "jobs.daily-email.1")
                             (j/key "jobs.weekly-email.1")]))

(defstate email-jobs
  :start (add-jobs scheduler)
  :stop (remove-jobs scheduler))
