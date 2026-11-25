use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoop};
use tao::window::WindowBuilder;
use wry::WebViewBuilder;

const PAGE: &str = r#"<!doctype html>
<html><body style="margin:0;background:#20a060;font:28px sans-serif">
<button id="b" style="margin:60px;width:300px;height:120px;font-size:28px;background:#ffcc00">Increment</button>
<script>
let count = 0;
document.title = "webkit-counter " + count;
document.getElementById("b").addEventListener("click", () => { count += 1; document.title = "webkit-counter " + count; });
</script></body></html>"#;

fn main() -> wry::Result<()> {
    let event_loop = EventLoop::new();
    let window = std::rc::Rc::new(WindowBuilder::new()
        .with_title("webkit-counter")
        .with_inner_size(tao::dpi::LogicalSize::new(640.0, 400.0))
        .build(&event_loop)
        .expect("window"));
    let builder = WebViewBuilder::new()
        .with_html(PAGE)
        .with_document_title_changed_handler({
            let window = window.clone();
            move |title| window.set_title(&title)
        });
    #[cfg(target_os = "linux")]
    let _webview = {
        use tao::platform::unix::WindowExtUnix;
        use wry::WebViewBuilderExtUnix;
        builder.build_gtk(window.default_vbox().expect("vbox"))?
    };
    #[cfg(not(target_os = "linux"))]
    let _webview = builder.build(&window)?;
    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;
        if let Event::WindowEvent { event: WindowEvent::CloseRequested, .. } = event {
            *control_flow = ControlFlow::Exit;
        }
    });
}
