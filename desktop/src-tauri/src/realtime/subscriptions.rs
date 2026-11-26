use std::collections::HashMap;

#[derive(Debug, PartialEq, Eq)]
pub enum EventRoute {
    Deliver,
    Discard,
    Resubscribe { after_seq: i64 },
}

struct Subscription {
    last_seq: i64,
    awaiting_replay: bool,
}

#[derive(Default)]
pub struct Subscriptions(HashMap<String, Subscription>);

impl Subscriptions {
    pub fn subscribe(&mut self, session_id: &str, after_seq: i64) {
        self.0.insert(
            session_id.to_string(),
            Subscription {
                last_seq: after_seq,
                awaiting_replay: true,
            },
        );
    }

    pub fn unsubscribe(&mut self, session_id: &str) {
        self.0.remove(session_id);
    }

    pub fn clear(&mut self) {
        self.0.clear();
    }

    pub fn confirm(&mut self, session_id: &str) {
        if let Some(subscription) = self.0.get_mut(session_id) {
            subscription.awaiting_replay = false;
        }
    }

    pub fn resubscribe_all(&mut self) -> Vec<(String, i64)> {
        self.0
            .iter_mut()
            .map(|(session_id, subscription)| {
                subscription.awaiting_replay = true;
                (session_id.clone(), subscription.last_seq)
            })
            .collect()
    }

    pub fn route(&mut self, session_id: &str, seq: i64) -> EventRoute {
        let Some(subscription) = self.0.get_mut(session_id) else {
            return EventRoute::Deliver;
        };
        if seq <= subscription.last_seq {
            return EventRoute::Discard;
        }
        if seq == subscription.last_seq + 1 {
            subscription.last_seq = seq;
            return EventRoute::Deliver;
        }
        if subscription.awaiting_replay {
            return EventRoute::Discard;
        }
        subscription.awaiting_replay = true;
        EventRoute::Resubscribe {
            after_seq: subscription.last_seq,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn confirmed(session_id: &str, after_seq: i64) -> Subscriptions {
        let mut subscriptions = Subscriptions::default();
        subscriptions.subscribe(session_id, after_seq);
        subscriptions.confirm(session_id);
        subscriptions
    }

    #[test]
    fn delivers_contiguous_events() {
        let mut subscriptions = confirmed("s", 10);
        assert_eq!(subscriptions.route("s", 11), EventRoute::Deliver);
        assert_eq!(subscriptions.route("s", 12), EventRoute::Deliver);
    }

    #[test]
    fn discards_already_seen_events() {
        let mut subscriptions = confirmed("s", 10);
        assert_eq!(subscriptions.route("s", 10), EventRoute::Discard);
        assert_eq!(subscriptions.route("s", 3), EventRoute::Discard);
    }

    #[test]
    fn resubscribes_from_last_contiguous_seq_on_gap() {
        let mut subscriptions = confirmed("s", 10);
        assert_eq!(subscriptions.route("s", 11), EventRoute::Deliver);
        assert_eq!(
            subscriptions.route("s", 14),
            EventRoute::Resubscribe { after_seq: 11 }
        );
    }

    #[test]
    fn discards_further_gaps_until_replay_is_confirmed() {
        let mut subscriptions = confirmed("s", 10);
        assert_eq!(
            subscriptions.route("s", 13),
            EventRoute::Resubscribe { after_seq: 10 }
        );
        assert_eq!(subscriptions.route("s", 14), EventRoute::Discard);
        subscriptions.confirm("s");
        assert_eq!(subscriptions.route("s", 11), EventRoute::Deliver);
        assert_eq!(subscriptions.route("s", 12), EventRoute::Deliver);
        assert_eq!(subscriptions.route("s", 13), EventRoute::Deliver);
        assert_eq!(subscriptions.route("s", 14), EventRoute::Deliver);
    }

    #[test]
    fn resubscribes_again_when_a_confirmed_replay_still_leaves_a_gap() {
        let mut subscriptions = confirmed("s", 10);
        assert_eq!(
            subscriptions.route("s", 20),
            EventRoute::Resubscribe { after_seq: 10 }
        );
        subscriptions.confirm("s");
        assert_eq!(subscriptions.route("s", 11), EventRoute::Deliver);
        assert_eq!(
            subscriptions.route("s", 21),
            EventRoute::Resubscribe { after_seq: 11 }
        );
    }

    #[test]
    fn discards_gaps_before_the_first_subscription_is_confirmed() {
        let mut subscriptions = Subscriptions::default();
        subscriptions.subscribe("s", 10);
        assert_eq!(subscriptions.route("s", 12), EventRoute::Discard);
        assert_eq!(subscriptions.route("s", 11), EventRoute::Deliver);
    }

    #[test]
    fn delivers_events_of_untracked_sessions() {
        let mut subscriptions = confirmed("s", 10);
        assert_eq!(subscriptions.route("other", 99), EventRoute::Deliver);
        subscriptions.unsubscribe("s");
        assert_eq!(subscriptions.route("s", 50), EventRoute::Deliver);
    }

    #[test]
    fn resubscribe_all_reports_last_contiguous_seqs_and_awaits_replay() {
        let mut subscriptions = confirmed("s", 10);
        assert_eq!(subscriptions.route("s", 11), EventRoute::Deliver);
        assert_eq!(subscriptions.resubscribe_all(), vec![("s".to_string(), 11)]);
        assert_eq!(subscriptions.route("s", 15), EventRoute::Discard);
    }
}
