# ZoneReplica

ZoneReplica 是一款專為 Paper (Minecraft 1.21.x 與 26.x) 伺服器設計的區域樣本複製插件。管理員以 A、B 兩點框出一座建築並記錄成「樣本」，之後可以把樣本覆蓋到任意數量的「場地」，支援跨世界與跨維度。樣本會完整保存方塊、方塊 NBT（箱子內容、告示牌文字、生怪磚設定等）與實體，原建築拆除後仍可使用。覆蓋採分批處理並以 Boss 血條顯示進度，可設定每週定時自動重置，適合小遊戲場地、活動地圖與重複使用的建築。

## 功能特性

* **完整快照**：
  * 記錄 A-B 範圍內的方塊狀態、方塊實體的完整 NBT 與實體（盔甲座、展示框、畫、生物等），原建築拆除後仍可覆蓋。
  * 可選擇把 A、B 兩個標記方塊在快照中記錄為空氣，避免副本裡多出空中的標記方塊。
  * 快照以調色盤加 GZIP 壓縮儲存，大片空氣幾乎不占空間（12.8 萬格的平坦區域約 15 KB）。
* **多場地覆蓋**：
  * 一個樣本可設定多個場地，C 點對應樣本的 A 點，可放在其他世界或維度。
  * 每個場地可各自設定：複製實體、清除原有實體、複製容器內容、覆蓋空氣。
  * 與樣本原位置或其他場地重疊時會發出警告。
* **不卡伺服器**：
  * 依區塊分批讀寫，每 tick 的方塊數與毫秒數上限都可設定，放置時不觸發物理更新。
  * 區塊以非同步方式載入，附近沒有玩家也能覆蓋；檔案讀寫全部在背景執行緒。
* **安全機制**：
  * 每次覆蓋前自動備份目標區域，可用 `/zr undo` 復原（保留份數可設定）。
  * 同一個場地已有任務時，可選擇「拒絕」、「排隊」或「中斷目前工作後執行」（沿用被中斷任務的備份）。
  * 伺服器在覆蓋途中關閉，重啟後會從中斷的位置自動接續。
* **自動更新**：
  * 依伺服器時間在指定星期與時刻自動覆蓋，倒數提醒提前開始，讓覆蓋剛好在設定的時刻開始。
  * 可在覆蓋前送走範圍內的玩家（重生點或指定位置），每個場地有自己的白名單與提示文字。
* **箱子介面**：
  * 建立、編輯、自動更新、白名單、確認等全部以箱子介面操作，一律只接受左鍵，物品無法被拿走。
  * 以特製選取工具點方塊設定座標，並以只有自己看得到的發光外框（或粒子）顯示範圍。
  * 介面未儲存就關閉時，暫存設定保留 5 分鐘（可設定）。
* **後台與指令方塊**：
  * update、undo、del、cancel、schedule、list 皆可由後台、RCON 或指令方塊執行，以 `-queue`、`-force`、`-confirm` 參數取代介面確認。
* **設定檔自動補齊與版本檢查**：
  * 插件啟動或執行 `/zr reload` 時，`config.yml` 與 `messages.yml` 缺少的新版欄位會自動補上，原有的設定值不會被覆蓋。
  * 所有文字（聊天訊息、介面標題、物品名稱與說明）都在 `messages.yml`，支援 `&` 色碼與 `&#RRGGBB`。
  * 啟動時與 `/zr version` 會向 GitHub Releases 檢查新版本，有新版時管理員登入 3 秒後收到提醒。

## 指令與權限

本插件主指令為 `/zonereplica`，別名 `/zr`。

| 指令 | 說明 | 執行端 | 權限節點 | 預設 |
| :--- | :--- | :--- | :--- | :--- |
| `/zr` 或 `/zr version` | 顯示版本並檢查更新 | 遊戲內 / 控制台 | `zonereplica.use` / `zonereplica.version` | OP |
| `/zr help` | 顯示自己有權限的指令 | 遊戲內 / 控制台 | `zonereplica.help` | OP |
| `/zr create <樣本>` | 開啟建立樣本介面 | 僅限玩家 | `zonereplica.create` | OP |
| `/zr place <樣本> <場地>` | 開啟設定場地介面 | 僅限玩家 | `zonereplica.place` | OP |
| `/zr update <樣本> [場地] [-queue\|-force]` | 執行覆蓋（不指定場地時覆蓋全部場地） | 遊戲內 / 控制台 | `zonereplica.update` | OP |
| `/zr edit <樣本> [場地]` | 編輯樣本或場地 | 僅限玩家 | `zonereplica.edit` | OP |
| `/zr del <樣本> [場地] [-confirm]` | 刪除樣本（連同場地）或場地 | 遊戲內 / 控制台 | `zonereplica.delete` | OP |
| `/zr undo <樣本> <場地> [-confirm] [-queue\|-force]` | 復原最近一次覆蓋 | 遊戲內 / 控制台 | `zonereplica.undo` | OP |
| `/zr cancel <樣本> [場地]` | 中止進行中或排隊中的任務 | 遊戲內 / 控制台 | `zonereplica.cancel` | OP |
| `/zr schedule [樣本] [場地]` | 查看任務與進度（玩家會看到 Boss 血條） | 遊戲內 / 控制台 | `zonereplica.schedule` | OP |
| `/zr list` | 樣本列表（玩家開啟介面） | 遊戲內 / 控制台 | `zonereplica.list` | OP |
| `/zr reload` | 重新載入並補齊 config.yml 與 messages.yml | 遊戲內 / 控制台 | `zonereplica.reload` | OP |
| （登入提醒） | 有新版本時，登入 3 秒後收到提醒 | — | `zonereplica.version` | OP |

> 權限 `zonereplica.admin` 包含以上全部權限。
>
> 參數（後台與指令方塊用）：
> * `-queue`：場地已有任務時排隊。
> * `-force`：中斷目前的任務後執行（已放下的部分維持原狀，沿用被中斷任務的備份）。
> * `-confirm`：確認刪除或復原。
> * 沒有加參數時，遇到衝突一律拒絕；玩家則會看到確認介面。

## 使用範例

### 1. 建立樣本並覆蓋到另一個場地
1. 輸入下列指令開啟建立介面：
   ```bash
   /zr create castle
   ```
2. 點「設定 A 點」取得選取工具，左鍵點建築的一角；再點「設定 B 點」，左鍵點對角。
3. 設定「移除 AB 標記方塊」後按「儲存並記錄快照」。
4. 建立場地並用選取工具點 C 點（對應 A 點），設定四個覆蓋選項與自動更新：
   ```bash
   /zr place castle east
   ```
5. 執行覆蓋（玩家需在確認介面按「確認執行」）：
   ```bash
   /zr update castle east
   ```

### 2. 後台或指令方塊執行
覆蓋 castle 的所有場地，有任務進行中時排隊：
```bash
zr update castle -queue
```
復原最近一次覆蓋：
```bash
zr undo castle east -confirm
```

### 3. 每週自動重置場地
在場地介面開啟「自動更新設定」：啟用、新增時刻（例如 `04:00`）、勾選星期、開啟「送走範圍內玩家」。
* 覆蓋前依 `auto-update.warning-seconds` 倒數提醒範圍內的玩家（預設 60、30、10、5、4、3、2、1 秒）。
* 倒數結束後把非白名單玩家送到重生點或指定位置，再開始覆蓋。
* 伺服器關機期間錯過的時刻不會補跑。

## 檔案位置

| 路徑 | 說明 |
| :--- | :--- |
| `plugins/ZoneReplica/config.yml` | 設定檔 |
| `plugins/ZoneReplica/messages.yml` | 所有顯示文字 |
| `plugins/ZoneReplica/data/zones/<樣本>.yml` | 樣本與所有場地的設定（可手動編輯） |
| `plugins/ZoneReplica/data/snapshots/<樣本>.zrs` | 樣本快照 |
| `plugins/ZoneReplica/data/backups/<樣本>/<場地>/<序號>.zrs` | 覆蓋前備份 |
| `plugins/ZoneReplica/data/tasks/<編號>.yml` | 未完成任務的進度（重啟後接續用） |

## 相容性

* Paper 1.21.x 與 26.x（26.x 伺服器本身需要 Java 25）；Purpur 等 Paper 分支理論上相容。
* 一個 jar 涵蓋全部支援版本，只使用 Paper API，不依賴伺服器內部程式碼。
* 支援 Multiverse 等多世界插件；世界未載入時手動覆蓋會被拒絕，自動更新則略過該次並記錄在後台。

---

# ZoneReplica

ZoneReplica is a zone template replication plugin designed for Paper (Minecraft 1.21.x and 26.x) servers. Administrators mark a build with two corners (A and B) and record it as a "sample", then stamp that sample onto any number of "placements", including in other worlds and dimensions. Samples store blocks, full block-entity NBT (chest contents, sign text, spawner settings, etc.) and entities, so they keep working even after the original build is removed. Placement runs in batches with a boss bar progress display and can be scheduled to reset automatically every week, ideal for minigame arenas, event maps and reusable builds.

## Features

* **Complete Snapshots**:
  * Records block states, full block-entity NBT and entities (armor stands, item frames, paintings, mobs, etc.) inside the A-B region.
  * Optionally stores the A and B marker blocks as air so copies don't contain floating marker blocks.
  * Snapshots use a palette with GZIP compression; large air regions take almost no space (a 128k-block flat area is about 15 KB).
* **Multiple Placements**:
  * Each sample can have many placements. Point C maps to point A of the sample and may be in another world or dimension.
  * Per-placement options: copy entities, clear existing entities, copy container contents, replace air.
  * Warns when a placement overlaps the sample region or another placement.
* **Lag-Free**:
  * Reads and writes chunk by chunk with configurable blocks-per-tick and milliseconds-per-tick limits; placed blocks do not trigger physics.
  * Chunks are loaded asynchronously, so placements work with no players nearby; all file I/O runs on background threads.
* **Safety**:
  * Every update backs up the target region first; `/zr undo` restores it (number of backups kept is configurable).
  * When a placement already has a task, choose Reject, Queue or Interrupt-and-run (reusing the interrupted task's backup).
  * If the server stops mid-update, the task resumes from where it left off after restart.
* **Scheduled Resets**:
  * Updates run automatically on selected weekdays and times (server time). The countdown starts early so the overwrite begins exactly at the configured time.
  * Optionally sends players in the area to spawn or a custom location first, with a per-placement whitelist and message.
* **Chest GUIs**:
  * Creating, editing, scheduling, whitelisting and confirmations all use chest GUIs that only respond to left-click; items can't be taken.
  * A special selection tool picks coordinates, and a glowing outline (or particles) visible only to you shows the region.
  * Unsaved GUI settings are kept for 5 minutes (configurable).
* **Console & Command Blocks**:
  * update, undo, del, cancel, schedule and list work from the console, RCON or command blocks, using `-queue`, `-force` and `-confirm` instead of GUI confirmation.
* **Auto-Updating Config & Update Checker**:
  * On startup or `/zr reload`, missing options in `config.yml` and `messages.yml` are added automatically without overwriting existing values.
  * All text (chat messages, GUI titles, item names and lore) lives in `messages.yml`, supporting `&` color codes and `&#RRGGBB`.
  * Checks GitHub Releases on startup and with `/zr version`; admins are notified 3 seconds after joining when an update is available.

## Commands & Permissions

The primary command for this plugin is `/zonereplica`, alias `/zr`.

| Command | Description | Executor | Permission Node | Default |
| :--- | :--- | :--- | :--- | :--- |
| `/zr` or `/zr version` | Show version and check for updates | In-game / Console | `zonereplica.use` / `zonereplica.version` | OP |
| `/zr help` | List the commands you have permission for | In-game / Console | `zonereplica.help` | OP |
| `/zr create <sample>` | Open the create-sample GUI | Player only | `zonereplica.create` | OP |
| `/zr place <sample> <placement>` | Open the placement GUI | Player only | `zonereplica.place` | OP |
| `/zr update <sample> [placement] [-queue\|-force]` | Run an update (all placements if none given) | In-game / Console | `zonereplica.update` | OP |
| `/zr edit <sample> [placement]` | Edit a sample or placement | Player only | `zonereplica.edit` | OP |
| `/zr del <sample> [placement] [-confirm]` | Delete a sample (with its placements) or a placement | In-game / Console | `zonereplica.delete` | OP |
| `/zr undo <sample> <placement> [-confirm] [-queue\|-force]` | Undo the most recent update | In-game / Console | `zonereplica.undo` | OP |
| `/zr cancel <sample> [placement]` | Cancel running or queued tasks | In-game / Console | `zonereplica.cancel` | OP |
| `/zr schedule [sample] [placement]` | Show tasks and progress (players see a boss bar) | In-game / Console | `zonereplica.schedule` | OP |
| `/zr list` | List samples (opens a GUI for players) | In-game / Console | `zonereplica.list` | OP |
| `/zr reload` | Reload and auto-complete config.yml and messages.yml | In-game / Console | `zonereplica.reload` | OP |
| (join notice) | Update notice 3 seconds after joining | — | `zonereplica.version` | OP |

> `zonereplica.admin` grants all of the above.
>
> Flags (for console and command blocks):
> * `-queue`: Queue if the placement already has a task.
> * `-force`: Interrupt the current task and run (blocks already placed stay; the interrupted task's backup is reused).
> * `-confirm`: Confirm a delete or undo.
> * Without flags, conflicts are rejected; players get a confirmation GUI instead.

## Usage Examples

### 1. Record a Sample and Stamp It Elsewhere
1. Open the create GUI:
   ```bash
   /zr create castle
   ```
2. Click "Set point A" to get the selection tool and left-click one corner of the build; click "Set point B" and left-click the opposite corner.
3. Choose whether to remove the A/B marker blocks, then click "Save and record snapshot".
4. Create a placement, left-click point C (maps to point A) with the selection tool, and set the four options plus auto-update:
   ```bash
   /zr place castle east
   ```
5. Run the update (players confirm in a GUI):
   ```bash
   /zr update castle east
   ```

### 2. From Console or Command Blocks
Update every placement of castle, queueing behind running tasks:
```bash
zr update castle -queue
```
Undo the most recent update:
```bash
zr undo castle east -confirm
```

### 3. Weekly Automatic Reset
In the placement GUI open "Auto-update settings": enable it, add a time (e.g. `04:00`), select weekdays and enable "Send players away".
* Players in the area get countdown warnings at `auto-update.warning-seconds` (default 60, 30, 10, 5, 4, 3, 2, 1 seconds).
* When the countdown ends, non-whitelisted players are sent to spawn or the custom location, then the update starts.
* Times missed while the server was off are not replayed.

## File Locations

| Path | Description |
| :--- | :--- |
| `plugins/ZoneReplica/config.yml` | Configuration |
| `plugins/ZoneReplica/messages.yml` | All display text |
| `plugins/ZoneReplica/data/zones/<sample>.yml` | Sample and placement settings (hand-editable) |
| `plugins/ZoneReplica/data/snapshots/<sample>.zrs` | Sample snapshot |
| `plugins/ZoneReplica/data/backups/<sample>/<placement>/<seq>.zrs` | Pre-update backups |
| `plugins/ZoneReplica/data/tasks/<id>.yml` | Progress of unfinished tasks (for resuming after restart) |

## Compatibility

* Paper 1.21.x and 26.x (26.x servers themselves require Java 25); Paper forks such as Purpur should also work.
* One jar covers every supported version; it uses only the Paper API with no server internals.
* Works with multi-world plugins such as Multiverse. If a world isn't loaded, manual updates are refused and scheduled updates are skipped and logged.
