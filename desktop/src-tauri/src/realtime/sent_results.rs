use std::collections::VecDeque;
use std::time::{Duration, Instant};

use serde_json::Value;

pub struct SentResults {
    retention: Duration,
    entries: VecDeque<(Instant, Value)>,
}

impl SentResults {
    pub fn new(retention: Duration) -> Self {
        Self {
            retention,
            entries: VecDeque::new(),
        }
    }

    pub fn record(&mut self, now: Instant, payload: Value) {
        self.prune(now);
        self.entries.push_back((now, payload));
    }

    pub fn take_recent(&mut self, now: Instant) -> Vec<Value> {
        self.prune(now);
        self.entries.drain(..).map(|(_, payload)| payload).collect()
    }

    fn prune(&mut self, now: Instant) {
        while self
            .entries
            .front()
            .is_some_and(|(sent_at, _)| now.duration_since(*sent_at) > self.retention)
        {
            self.entries.pop_front();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const RETENTION: Duration = Duration::from_secs(120);

    #[test]
    fn returns_results_sent_within_the_retention_in_order() {
        let start = Instant::now();
        let mut sent = SentResults::new(RETENTION);
        sent.record(start, json!({ "callId": "a" }));
        sent.record(start + Duration::from_secs(30), json!({ "callId": "b" }));

        let recent = sent.take_recent(start + Duration::from_secs(60));

        assert_eq!(
            recent,
            vec![json!({ "callId": "a" }), json!({ "callId": "b" })]
        );
    }

    #[test]
    fn drops_results_older_than_the_retention() {
        let start = Instant::now();
        let mut sent = SentResults::new(RETENTION);
        sent.record(start, json!({ "callId": "old" }));
        sent.record(start + Duration::from_secs(100), json!({ "callId": "new" }));

        let recent = sent.take_recent(start + Duration::from_secs(150));

        assert_eq!(recent, vec![json!({ "callId": "new" })]);
    }

    #[test]
    fn taking_the_results_empties_the_buffer() {
        let start = Instant::now();
        let mut sent = SentResults::new(RETENTION);
        sent.record(start, json!({ "callId": "a" }));

        sent.take_recent(start);

        assert!(sent.take_recent(start).is_empty());
    }

    #[test]
    fn recording_prunes_expired_results() {
        let start = Instant::now();
        let mut sent = SentResults::new(RETENTION);
        sent.record(start, json!({ "callId": "old" }));
        sent.record(start + Duration::from_secs(200), json!({ "callId": "new" }));

        assert_eq!(sent.entries.len(), 1);
    }
}
