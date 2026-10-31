use std::collections::HashMap;
use std::sync::Mutex;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use serde_json::{Value, json};
use tokio::task::AbortHandle;
use url::{Host, Url};

use super::Outgoing;

#[derive(Default)]
pub struct Relay {
    http: reqwest::Client,
    running: Mutex<HashMap<String, AbortHandle>>,
}

impl Relay {
    pub fn handle_request(self: &std::sync::Arc<Self>, payload: Value, outgoing: Outgoing) {
        let relay_id = payload["relayId"].as_str().unwrap_or_default().to_string();
        let relay = self.clone();
        let id = relay_id.clone();
        let task = tauri::async_runtime::spawn(async move {
            if let Err((code, message)) = relay.forward(&id, &payload, &outgoing).await {
                outgoing.send(
                    "relay.error",
                    json!({ "relayId": id, "code": code, "message": message }),
                );
            }
            relay.forget(&id);
        });
        if let Ok(mut running) = self.running.lock() {
            running.insert(relay_id, task.inner().abort_handle());
        }
    }

    pub fn cancel(&self, relay_id: &str) {
        if let Some(handle) = self
            .running
            .lock()
            .ok()
            .and_then(|mut running| running.remove(relay_id))
        {
            handle.abort();
        }
    }

    fn forget(&self, relay_id: &str) {
        if let Ok(mut running) = self.running.lock() {
            running.remove(relay_id);
        }
    }

    async fn forward(
        &self,
        relay_id: &str,
        payload: &Value,
        outgoing: &Outgoing,
    ) -> Result<(), (&'static str, String)> {
        let url = payload["url"].as_str().unwrap_or_default();
        let parsed = Url::parse(url).map_err(|error| ("connection_failed", error.to_string()))?;
        if !is_loopback(&parsed) {
            return Err((
                "host_not_allowed",
                format!("Only local addresses can be relayed, got {url}"),
            ));
        }
        let method =
            reqwest::Method::from_bytes(payload["method"].as_str().unwrap_or("GET").as_bytes())
                .map_err(|error| ("connection_failed", error.to_string()))?;
        let mut request = self.http.request(method, parsed);
        if let Some(headers) = payload["headers"].as_object() {
            for (name, value) in headers {
                if let Some(value) = value.as_str() {
                    request = request.header(name, value);
                }
            }
        }
        if let Some(body) = payload["body"].as_str() {
            let bytes = STANDARD
                .decode(body)
                .map_err(|error| ("connection_failed", error.to_string()))?;
            request = request.body(bytes);
        }
        let mut response = request
            .send()
            .await
            .map_err(|error| ("connection_failed", error.to_string()))?;
        outgoing.send(
            "relay.response.start",
            json!({ "relayId": relay_id, "status": response.status().as_u16(), "headers": {} }),
        );
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|error| ("connection_failed", error.to_string()))?
        {
            outgoing.send(
                "relay.response.chunk",
                json!({ "relayId": relay_id, "data": STANDARD.encode(&chunk) }),
            );
        }
        outgoing.send("relay.response.end", json!({ "relayId": relay_id }));
        Ok(())
    }
}

fn is_loopback(url: &Url) -> bool {
    match url.host() {
        Some(Host::Domain(domain)) => domain.eq_ignore_ascii_case("localhost"),
        Some(Host::Ipv4(address)) => address.is_loopback(),
        Some(Host::Ipv6(address)) => address.is_loopback(),
        None => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_loopback_hosts_are_relayed() {
        for allowed in [
            "http://localhost:11434/v1",
            "http://127.0.0.1:1234/v1",
            "http://[::1]:8080/v1",
        ] {
            assert!(is_loopback(&Url::parse(allowed).unwrap()), "{allowed}");
        }
        for denied in [
            "http://192.168.1.10:11434/v1",
            "https://api.openai.com/v1",
            "http://localhost.evil.com/v1",
        ] {
            assert!(!is_loopback(&Url::parse(denied).unwrap()), "{denied}");
        }
    }
}
