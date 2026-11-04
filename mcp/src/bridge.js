import { readFile, stat } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

/** A failure whose message is fit to show to the person using the AI app. */
export class BridgeError extends Error {}

/**
 * Where the Craftium mod writes the port and token of its AI bridge, most specific first. A launcher that keeps each
 * profile in its own folder (Prism, Modrinth App, CurseForge) is reached with CRAFTIUM_MINECRAFT_DIR or CRAFTIUM_BRIDGE_FILE.
 */
export function candidateFiles(env = process.env, platform = process.platform, home = os.homedir()) {
  const files = [];
  if (env.CRAFTIUM_BRIDGE_FILE) files.push(env.CRAFTIUM_BRIDGE_FILE);
  if (env.CRAFTIUM_MINECRAFT_DIR) files.push(path.join(env.CRAFTIUM_MINECRAFT_DIR, "config", "craftium", "bridge.json"));
  let minecraft;
  if (platform === "win32") minecraft = path.join(env.APPDATA || path.join(home, "AppData", "Roaming"), ".minecraft");
  else if (platform === "darwin") minecraft = path.join(home, "Library", "Application Support", "minecraft");
  else minecraft = path.join(home, ".minecraft");
  files.push(path.join(minecraft, "config", "craftium", "bridge.json"));
  return files;
}

export const NOT_RUNNING =
  "Craftium is not reachable. Start Minecraft with the Craftium mod, open a world, and type /craftium ai on in the game chat. " +
  "If Minecraft uses a separate profile folder, set the Minecraft folder in this extension's settings.";

export class Bridge {
  /** @param {{files?: string[], fetchImpl?: typeof fetch, timeoutMs?: number}} options */
  constructor({ files = candidateFiles(), fetchImpl = fetch, timeoutMs = 30000 } = {}) {
    this.files = files;
    this.fetch = fetchImpl;
    this.timeoutMs = timeoutMs;
  }

  /** The newest bridge file that exists, read; the game deletes its file when the bridge closes. */
  async connection() {
    let newest = null;
    for (const file of this.files) {
      try {
        const modified = (await stat(file)).mtimeMs;
        if (!newest || modified > newest.modified) newest = { file, modified };
      } catch {
        // not there
      }
    }
    if (!newest) throw new BridgeError(NOT_RUNNING);
    try {
      const info = JSON.parse(await readFile(newest.file, "utf8"));
      if (!Number.isInteger(info.port) || typeof info.token !== "string") throw new Error("malformed");
      return info;
    } catch {
      throw new BridgeError(`The Craftium bridge file ${newest.file} could not be read. Turn the bridge off and on again with /craftium ai off, then /craftium ai on.`);
    }
  }

  /** Calls one bridge tool and returns its JSON reply. A reply with ok:false is returned, not thrown, for the caller to report. */
  async call(tool, args = {}) {
    const { port, token } = await this.connection();
    let response;
    try {
      response = await this.fetch(`http://127.0.0.1:${port}/${tool}`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(args),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch (error) {
      if (error?.name === "TimeoutError") throw new BridgeError("Minecraft did not answer in time. Is the game paused, loading or frozen?");
      throw new BridgeError(NOT_RUNNING);
    }
    if (response.status === 401 || response.status === 403) {
      throw new BridgeError("Craftium refused the connection. Minecraft may have restarted; try again, or run /craftium ai off and /craftium ai on.");
    }
    let body;
    try {
      body = await response.json();
    } catch {
      throw new BridgeError(`Craftium sent a reply that was not understood (HTTP ${response.status}).`);
    }
    return body;
  }
}
