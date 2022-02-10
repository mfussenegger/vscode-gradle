import { GradleTaskTreeItem } from "../views";
import { runTask } from "../tasks/taskUtil";
import { Command } from "./Command";
import { RootProjectsStore } from "../stores";
import { GradleClient } from "../client";
import { checkDoubleClick } from "./utils";

export const COMMAND_RUN_TASK = "gradle.runTask";
export const COMMAND_RUN_TASK_DOUBLE_CLICK = "gradle.runTaskDoubleClick";

async function run(treeItem: GradleTaskTreeItem, rootProjectsStore: RootProjectsStore, client: GradleClient) {
    if (treeItem && treeItem.task) {
        await runTask(rootProjectsStore, treeItem.task, client);
    }
}

export class RunTaskCommand extends Command {
    constructor(private rootProjectsStore: RootProjectsStore, private client: GradleClient) {
        super();
    }

    async run(treeItem: GradleTaskTreeItem): Promise<void> {
        run(treeItem, this.rootProjectsStore, this.client);
    }
}

export class RunTaskDoubleClickCommand extends Command {
    constructor(private rootProjectsStore: RootProjectsStore, private client: GradleClient) {
        super();
    }

    async run(treeItem: GradleTaskTreeItem): Promise<void> {
        if (checkDoubleClick()) {
            return run(treeItem, this.rootProjectsStore, this.client);
        }
    }
}
