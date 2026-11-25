const path = require("node:path");
const { app, BrowserWindow } = require("electron");

app.whenReady().then(() => {
  const window = new BrowserWindow({ width: 640, height: 480, x: 100, y: 80, title: "electron-todo" });
  window.setMenuBarVisibility(false);
  window.webContents.on("did-finish-load", () => console.log("electron-todo ready"));
  window.loadFile(path.join(__dirname, "index.html"));
});

app.on("window-all-closed", () => app.quit());
