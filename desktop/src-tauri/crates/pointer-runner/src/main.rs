mod config;
mod gateway;
mod repo;

use std::process::ExitCode;

#[tokio::main]
async fn main() -> ExitCode {
    let config = match config::Config::from_env() {
        Ok(config) => config,
        Err(error) => {
            eprintln!("pointer-runner: {error}");
            return ExitCode::FAILURE;
        }
    };
    if let Err(error) = std::fs::create_dir_all(&config.workspace) {
        eprintln!("pointer-runner: cannot create the workspace: {error}");
        return ExitCode::FAILURE;
    }
    gateway::run(config).await;
    ExitCode::SUCCESS
}
