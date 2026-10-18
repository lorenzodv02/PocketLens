const { app, BrowserWindow, ipcMain, shell } = require("electron");
const crypto = require("node:crypto");
const { spawn, spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const { startServer } = require("./server");

let receiver;
let mainWindow;
let virtualCameraProcess = null;
let virtualCameraError = null;
let virtualCameraInstallInProgress = false;

async function createWindow() {
  receiver = await startServer();
  if (getVirtualCameraStatus().installed) {
    try {
      await startVirtualCameraHost();
    } catch (error) {
      virtualCameraError = error.message;
      console.error("Could not automatically start PocketLens Camera:", error);
    }
  }

  mainWindow = new BrowserWindow({
    width: 1240,
    height: 900,
    minWidth: 900,
    minHeight: 680,
    title: "PocketLens",
    backgroundColor: "#080d1a",
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      preload: path.join(__dirname, "preload.js")
    }
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith("http://") || url.startsWith("https://")) {
      shell.openExternal(url);
    }
    return { action: "deny" };
  });

  const connectionInfo = JSON.stringify(receiver.addresses.map((address) => `${address}:${receiver.port}`));
  const startUrl = new URL(`http://127.0.0.1:${receiver.port}/`);
  startUrl.searchParams.set("desktop", "1");
  startUrl.searchParams.set("addresses", connectionInfo);
  await mainWindow.loadURL(startUrl.toString());
}

function getVirtualCameraPaths() {
  const resourcesPath = app.isPackaged ? process.resourcesPath : __dirname;
  const installRoot = path.join(
    process.env.ProgramFiles || "C:\\Program Files",
    "PocketLens"
  );
  return {
    installRoot,
    source: path.join(resourcesPath, "virtual-camera", "PocketLensVirtualCameraSource.dll"),
    installer: path.join(resourcesPath, "virtual-camera", "Install-PocketLensVirtualCamera.ps1")
  };
}

function getVirtualCameraStatus() {
  const paths = getVirtualCameraPaths();
  const registryKey = "HKLM\\SOFTWARE\\Classes\\CLSID\\{3cad447d-f283-4af4-a3b2-6f5363309f52}\\InprocServer32";
  const registry = spawnSync(
    path.join(process.env.WINDIR || "C:\\Windows", "System32", "reg.exe"),
    ["query", registryKey, "/ve"],
    { encoding: "utf8", windowsHide: true }
  );
  const registeredPath = registry.status === 0
    ? registry.stdout.match(/REG_SZ\s+(.+?)\s*$/im)?.[1] || null
    : null;
  const registeredDirectory = registeredPath ? path.dirname(registeredPath) : null;
  const launcher = registeredDirectory && path.dirname(registeredDirectory) === paths.installRoot
    ? path.join(registeredDirectory, "PocketLensVirtualCamera.exe")
    : null;
  const registeredSourceHash = registeredPath && fs.existsSync(registeredPath)
    ? crypto.createHash("sha256").update(fs.readFileSync(registeredPath)).digest("hex")
    : null;
  const bundledSourceHash = fs.existsSync(paths.source)
    ? crypto.createHash("sha256").update(fs.readFileSync(paths.source)).digest("hex")
    : null;
  const registered = Boolean(registeredPath && launcher && fs.existsSync(launcher) && registeredSourceHash);
  const installed = Boolean(
    registered &&
    registeredSourceHash === bundledSourceHash
  );

  return {
    installed,
    updateAvailable: registered && !installed,
    running: Boolean(virtualCameraProcess && virtualCameraProcess.exitCode === null && virtualCameraProcess.signalCode === null),
    installInProgress: virtualCameraInstallInProgress,
    error: virtualCameraError,
    installPath: registeredDirectory || path.join(paths.installRoot, "VirtualCamera")
  };
}

function startVirtualCameraHost() {
  const status = getVirtualCameraStatus();
  if (!status.installed) {
    return Promise.reject(new Error("Install the PocketLens virtual camera first."));
  }
  if (status.running) return Promise.resolve(status);

  const { installPath } = getVirtualCameraStatus();
  const launcher = path.join(installPath, "PocketLensVirtualCamera.exe");
  virtualCameraError = null;
  const child = spawn(launcher, ["--background"], {
    cwd: path.dirname(launcher),
    windowsHide: true,
    stdio: "ignore"
  });
  virtualCameraProcess = child;
  child.once("error", (error) => {
    if (virtualCameraProcess !== child) return;
    virtualCameraError = error.message;
    virtualCameraProcess = null;
    console.error("Could not start PocketLens virtual camera:", error);
  });
  child.once("exit", (code, signal) => {
    if (virtualCameraProcess !== child) return;
    virtualCameraProcess = null;
    if (code !== 0 && code !== null) {
      virtualCameraError = `Camera host exited with code ${code}${signal ? ` (${signal})` : ""}.`;
    }
  });
  return new Promise((resolve, reject) => {
    child.once("spawn", () => resolve(getVirtualCameraStatus()));
    child.once("error", reject);
    child.once("exit", (code, signal) => {
      reject(new Error(`Camera host exited during startup (${code ?? signal ?? "unknown"}).`));
    });
  });
}

function registerVirtualCameraHandlers() {
  ipcMain.handle("virtual-camera:status", () => getVirtualCameraStatus());
  ipcMain.handle("virtual-camera:install", async () => {
    if (virtualCameraInstallInProgress) {
      throw new Error("Virtual camera installation is already running.");
    }

    const { installer } = getVirtualCameraPaths();
    if (!fs.existsSync(installer)) {
      throw new Error(`The virtual camera installer was not found: ${installer}`);
    }

    virtualCameraInstallInProgress = true;
    virtualCameraError = null;
    try {
      const scriptPath = installer.replace(/'/g, "''");
      const command = `$ErrorActionPreference='Stop'; $p=Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "' + '${scriptPath}' + '"'); exit $p.ExitCode`;
      const output = [];
      const powershell = spawn(
        path.join(process.env.WINDIR || "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe"),
        ["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
        { windowsHide: false, stdio: ["ignore", "pipe", "pipe"] }
      );
      powershell.stdout.setEncoding("utf8").on("data", (text) => output.push(text));
      powershell.stderr.setEncoding("utf8").on("data", (text) => output.push(text));
      const exitCode = await new Promise((resolve, reject) => {
        powershell.once("error", reject);
        powershell.once("close", resolve);
      });
      if (exitCode !== 0) {
        const details = output.join("").trim();
        throw new Error(`Virtual camera installer exited with code ${exitCode}.${details ? ` ${details}` : " Approve the administrator prompt and try again."}`);
      }
      return getVirtualCameraStatus();
    } catch (error) {
      virtualCameraError = error.message;
      throw error;
    } finally {
      virtualCameraInstallInProgress = false;
    }
  });

  ipcMain.handle("virtual-camera:start", () => {
    return startVirtualCameraHost();
  });

  ipcMain.handle("virtual-camera:stop", async () => {
    const child = virtualCameraProcess;
    if (child && child.exitCode === null && child.signalCode === null) {
      await new Promise((resolve, reject) => {
        child.once("error", reject);
        child.once("exit", resolve);
        if (!child.kill()) reject(new Error("Could not stop the PocketLens camera host."));
      });
    }
    return getVirtualCameraStatus();
  });
}

registerVirtualCameraHandlers();

app.whenReady().then(createWindow).catch((error) => {
  console.error("Could not start PocketLens:", error);
  app.quit();
});

app.on("window-all-closed", () => app.quit());

app.on("before-quit", () => {
  if (virtualCameraProcess && virtualCameraProcess.exitCode === null) {
    virtualCameraProcess.kill();
  }
  if (!receiver) return;
  receiver.discovery.close();
  if (receiver.server.closeAllConnections) receiver.server.closeAllConnections();
  receiver.server.close();
});
