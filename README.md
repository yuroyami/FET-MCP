# fet-mcp

fet-mcp lets an AI assistant build school timetables with [FET](https://lalescu.ro/liviu/fet/), the free timetabling program.

You describe your school in a chat. The assistant uses fet-mcp to write the timetable file, run FET, and read the result back to you.

- [What it is](#what-it-is)
- [Why it exists](#why-it-exists)
- [How to set it up](#how-to-set-it-up)
- [How to use it](#how-to-use-it)
- [Reference](#reference)
- [For developers](#for-developers)
- [License](#license)

## What it is

Three parts work together:

- **FET** is a free program that makes school timetables. You give it teachers, classes, subjects, lessons and rules. FET then searches for a weekly timetable that obeys every rule.
- **MCP** (Model Context Protocol) is an open standard that gives an AI assistant new tools. Claude Code, Claude Desktop and many other assistants support it. A program that supplies such tools is an MCP server.
- **fet-mcp** is an MCP server for FET. It gives the assistant 52 tools. With these tools, the assistant can create and change FET files, start FET, and read FET's results.

This README uses FET's own words for two things:

- An **activity** is a lesson, for example "4 hours of Math for class 1A with Mr. Smith".
- A **constraint** is a rule, for example "Mr. Smith does not work on Monday".

A short example. You write this in the chat:

> Class 1A has 4 hours of Math with Mr. Smith. Never put two of these hours on the same day.

The assistant then calls the tools:

1. `fet_add_activities` adds the 4 Math hours.
2. `fet_add_constraints` adds a constraint that keeps the 4 hours on different days.
3. `fet_validate` checks the file.

## Why it exists

FET is powerful, but it is difficult to set up:

- FET knows 344 types of constraints.
- FET has four modes, and each mode allows a different set of constraints.
- When no timetable is possible, the reason is often hard to find.

An assistant that writes FET's XML files directly makes mistakes. For example, a wrong field name can break the file or change a rule without a warning.

fet-mcp removes these problems:

- The assistant never writes XML. It calls tools, and the server checks every change before it writes the file.
- The server knows every constraint type and its fields. This list comes from FET's own source code.
- FET runs in the background. The assistant can check the progress while FET works.
- When FET cannot place a lesson, the server names that lesson. So the next attempt is a fix, not a guess.
- The timetable file on disk is always up to date. You can open it in the FET program at any time and see what the assistant did.

## How to set it up

The commands below are for macOS with [Homebrew](https://brew.sh). On Linux, install the same tools with your package manager. The setup needs about 3 GB of disk space.

### 1. Install the tools

1. Install JDK 21 (the Java Development Kit, version 21), for example from [Adoptium](https://adoptium.net). The build uses exactly this version.
2. Check the installation:

   ```bash
   java -version
   ```

3. Install CMake and Qt 6. FET needs them to build its command-line program. Qt is a large download, about 1 to 2 GB.

   ```bash
   brew install cmake qt
   ```

### 2. Get the code

```bash
git clone https://github.com/yuroyami/FET-MCP.git
cd FET-MCP
```

### 3. Download FET

FET's source code is not in this repository. It is 354 MB, and it belongs to the FET authors. Run this script to download it:

```bash
./scripts/fetch-fet.sh
```

The script downloads FET 7.10.4. It checks the download against the SHA256 checksum that FET publishes. Then it unpacks FET into the `_upstream/` folder.

### 4. Build fet-cl

`fet-cl` is FET's command-line program. The server runs it to make timetables. FET does not publish `fet-cl` for macOS, so you build it from the source code:

```bash
cd _upstream/fet-7.10.4
qt-cmake -B build -DCOMMAND_LINE_ONLY=ON
cmake --build build --parallel 8
cd ../..
```

The program is now at `_upstream/fet-7.10.4/build/src/cl/fet-cl`.

### 5. Build the server

```bash
./gradlew shadowJar
```

The server is now at `build/libs/fet-mcp.jar`.

### 6. Make a workspace

The workspace is the only folder that the server can read and write. Your timetable files go there.

```bash
mkdir -p ~/timetables
```

### 7. Connect the server to your assistant

Use absolute paths. In the commands below, replace `/path/to/FET-MCP` with the folder of this repository.

**Claude Code.** Run this command:

```bash
claude mcp add fet \
  -e FET_WORKSPACE="$HOME/timetables" \
  -e FET_CL_PATH=/path/to/FET-MCP/_upstream/fet-7.10.4/build/src/cl/fet-cl \
  -- java -jar /path/to/FET-MCP/build/libs/fet-mcp.jar
```

**Claude Desktop.** Add this to the file `claude_desktop_config.json`, then start Claude Desktop again:

```json
{
  "mcpServers": {
    "fet": {
      "command": "java",
      "args": ["-jar", "/path/to/FET-MCP/build/libs/fet-mcp.jar"],
      "env": {
        "FET_WORKSPACE": "/path/to/your/timetables",
        "FET_CL_PATH": "/path/to/FET-MCP/_upstream/fet-7.10.4/build/src/cl/fet-cl"
      }
    }
  }
}
```

Other MCP clients need the same command, arguments and environment variables.

### 8. Check the connection

Start a new chat and write:

> Call fet_state.

The assistant tells you that no document is open. This answer shows that the server works.

## How to use it

### A typical session

1. **Start a file.** Ask for a new timetable, or open an existing `.fet` file from the workspace.
2. **Describe the week.** Give the days and the hours of each day.
3. **Add the school.** Give the subjects, teachers, classes and rooms.
4. **Add the activities.** For each class, give the subject, the teacher and the hours per week.
5. **Add the constraints.** For example: when teachers are free, when breaks are, and how many hours a class can have in one day.
6. **Check the data.** Ask the assistant to validate the file.
7. **Generate.** Ask the assistant to make the timetable. Give a time limit, for example 10 minutes.
8. **Read the result.** Ask for the timetable of one teacher or one class.
9. **Improve the result.** Lock the parts that you like, change a constraint, and generate again.

The server also supplies three ready-made prompts for this work: `build_timetable`, `fix_impossible` and `explain_timetable`.

### Good to know

- Every constraint has a weight. Weight 100 means that FET can never break the constraint. A lower weight means that FET tries to obey it.
- Too many constraints at weight 100 is the usual cause of an impossible timetable.
- The assistant can undo every change with `fet_undo`.
- The server writes the timetable file after every change. If you change the same file in the FET program, ask the assistant to open the file again. Until then, the server refuses the next change.
- A generation can take a long time. It runs in the background, and the assistant can check its progress.
- A finished timetable does not go into your file by itself. Ask the assistant to take it with `fet_results_adopt`, or to keep some lessons in place with `fet_lock`.

## Reference

### Settings

Set these environment variables in the configuration of your MCP client.

| Variable | Meaning |
|---|---|
| `FET_WORKSPACE` | Required. The only folder that the server can read and write. |
| `FET_CL_PATH` | The path to `fet-cl`. Without it, the assistant can edit files but cannot generate timetables. |
| `FET_LANGUAGE` | The language of the timetables that FET writes. The default is `en_US`. |
| `FET_DEFAULT_TIME_LIMIT` | The time limit of a generation in seconds, when the assistant gives none. The default is 600. |
| `FET_LOG_LEVEL` | How much the server logs to stderr. The default is `warn`. |
| `FET_ALLOW_VERSION_MISMATCH` | Set to `true` to start even when `fet-cl` is not FET 7.10.4. |

### The four modes

FET saves a mode in every timetable file. The mode sets which constraints exist. The server refuses a constraint that does not fit the mode.

| Mode | Use | Constraint types |
|---|---|---|
| Official | The usual weekly timetable. | 207 |
| Mornings-Afternoons | Each real day is two FET days: a morning and an afternoon. Schools in Algeria and Morocco use this mode. | 329 |
| Block Planning | FET days stand for teachers or blocks, and hours stand for real time slots. | 210 |
| Terms | The school year is a series of terms. Schools in Finland use this mode. | 213 |

In Mornings-Afternoons mode, you do not count FET days by hand. Give the real days to `fet_set_week`, and the server makes the morning and afternoon days. Before you use this mode, ask the assistant to call `fet_explain_mode`.

### The tools

52 tools in twelve groups:

- **File**: `fet_new`, `fet_open`, `fet_save`
- **State**: `fet_state`, `fet_explain_mode`, `fet_set_mode`
- **Week**: `fet_set_week`, `fet_set_terms`
- **School data**: `fet_add_subjects`, `fet_add_activity_tags`, `fet_add_teachers`, `fet_add_buildings`, `fet_add_rooms`, `fet_add_years`, `fet_add_groups`, `fet_add_subgroups`, `fet_divide_year`, `fet_update`, `fet_remove`, `fet_get`, `fet_list`
- **Activities**: `fet_add_activities`, `fet_update_activities`, `fet_remove_activities`, `fet_set_activities_active`, `fet_list_activities`
- **Constraints**: `fet_constraint_types`, `fet_add_constraints`, `fet_update_constraint`, `fet_remove_constraints`, `fet_list_constraints`
- **Common constraints**: `fet_set_unavailable`, `fet_set_breaks`, `fet_set_limits`, `fet_set_rooms_preference`, `fet_pin_activities`, `fet_group_activities_in_initial_order`
- **Checks**: `fet_validate`, `fet_precheck`
- **Generation**: `fet_generate_start`, `fet_generate_status`, `fet_generate_cancel`
- **Results**: `fet_results_summary`, `fet_results_placements`, `fet_results_view`, `fet_results_conflicts`, `fet_results_adopt`, `fet_export`
- **Locking**: `fet_lock`, `fet_unlock`
- **Undo**: `fet_undo`, `fet_redo`

The server also supplies 8 resources, for example `fet://document` and `fet://schema/constraints`, and the 3 prompts above.

### Try it without FET

The tests include a fake `fet-cl` that pretends to run FET. With it, you can try the server without steps 3 and 4. Connect this second server to Claude Code:

```bash
mkdir -p /tmp/fet-demo
claude mcp add fet-demo \
  -e FET_WORKSPACE=/tmp/fet-demo \
  -e FET_CL_PATH="$PWD/src/test/resources/fake-fet-cl.sh" \
  -- java -jar "$PWD/build/libs/fet-mcp.jar"
```

Run this command in the folder of this repository, after step 5.

## For developers

The server is written in Kotlin for JDK 21. It uses the [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) and talks to the assistant over stdio.

### How the parts fit

```
MCP client
    |  stdio
    v
FetMcpServer  ->  ToolRegistry  ->  DocumentSession  ->  .fet file on disk
                        |                  |
                        |                  +-> Snapshots (undo)
                        |                  +-> Validator, Cascades
                        +-> JobRunner  ->  fet-cl process
                                   |
                                   +-> ResultReader, Views
```

- The `.fet` file on disk is the source of truth. The server writes it after every successful change.
- `JobRunner` starts `fet-cl` as a separate process and keeps its output in `.fet-mcp/runs/` inside the workspace.
- `fet-cl` returns exit code 0 also when it proves that no timetable is possible. So the server reads the result line that FET prints, not the exit code.

### The constraint schema

The server does not have one tool for each of the 344 constraint types. A generator reads FET's C++ source code and writes `src/main/resources/fet/constraints.schema.json`. This file lists every type, its fields in the order FET writes them, and the modes that allow it. The assistant searches the list with `fet_constraint_types`, then calls `fet_add_constraints` with a type and its fields.

After an upgrade of FET, generate the schema again:

```bash
./gradlew schemagen -PfetSrc=_upstream/fet-<version>
```

Then run the tests. They compare the counts with FET's own mode checks, so a mismatch makes them fail.

### Tests

Do step 3 first: the tests read the 325 example files that come with FET.

```bash
./gradlew test
```

The tests read and write every example file and compare the result with the original. The generation tests use the fake `fet-cl`, so they do not need Qt.

## License

fet-mcp uses the GNU Affero General Public License v3, the same license as FET. See [LICENSE](LICENSE).

FET is made by Liviu Lalescu and Volker Dirr. fet-mcp runs `fet-cl` as a separate program and does not link FET's code.
