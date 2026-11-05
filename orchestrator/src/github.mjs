export class GitHub {
  constructor(config, { fetchImpl = fetch } = {}) {
    this.config = config;
    this.fetch = fetchImpl;
    this.base = `https://api.github.com/repos/${config.githubOwner}/${config.githubRepo}`;
  }

  async request(method, path, body) {
    const response = await this.fetch(`${this.base}${path}`, {
      method,
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${this.config.githubToken}`,
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "social-drive-codex-orchestrator/0.1",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(30_000),
    });
    if (!response.ok) {
      const error = new Error(`GitHub ${method} ${path}: ${response.status} ${await response.text()}`);
      error.status = response.status;
      throw error;
    }
    return response.status === 204 ? null : response.json();
  }

  async graphql(query, variables = {}) {
    const response = await this.fetch("https://api.github.com/graphql", {
      method: "POST",
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${this.config.githubToken}`,
        "Content-Type": "application/json",
        "User-Agent": "social-drive-codex-orchestrator/0.1",
      },
      body: JSON.stringify({ query, variables }),
      signal: AbortSignal.timeout(30_000),
    });
    if (!response.ok) throw new Error(`GitHub GraphQL: ${response.status}`);
    const result = await response.json();
    if (result.errors?.length) throw new Error(`GitHub GraphQL: ${result.errors.map((item) => item.type ?? "error").join(",")}`);
    return result.data;
  }

  listReadyIssues() {
    const label = encodeURIComponent(this.config.readyLabel);
    return this.request("GET", `/issues?state=open&labels=${label}&sort=created&direction=asc&per_page=100`)
      .then((items) => items.filter((item) => !item.pull_request));
  }

  getIssue(number) { return this.request("GET", `/issues/${number}`); }
  getPullRequest(number) { return this.request("GET", `/pulls/${number}`); }

  async requireOpenIssue(number) {
    if (!Number.isInteger(number) || number <= 0) throw new Error("Follow-up issue number must be a positive integer");
    const issue = await this.getIssue(number);
    if (issue.pull_request) throw new Error(`Follow-up target #${number} is a pull request`);
    if (issue.state !== "open") throw new Error(`Follow-up target #${number} is not open`);
    return issue;
  }

  comment(number, body) { return this.request("POST", `/issues/${number}/comments`, { body }); }
  async listAllIssueComments(number) {
    const comments = [];
    for (let page = 1; page <= 100; page += 1) {
      const batch = await this.request("GET", `/issues/${number}/comments?per_page=100&page=${page}`);
      comments.push(...batch);
      if (batch.length < 100) return comments;
    }
    throw new Error(`GitHub issue #${number} has too many comments to reconcile safely`);
  }

  async upsertProgressComment(number, body, knownCommentId = null) {
    if (knownCommentId) {
      try {
        const updated = await this.request("PATCH", `/issues/comments/${knownCommentId}`, { body });
        return updated.id;
      } catch (error) {
        if (error?.status !== 404) throw error;
      }
    }
    const marker = `<!-- social-drive-control-center:issue-${number} -->`;
    const comments = await this.listAllIssueComments(number);
    const existing = comments.find((comment) => String(comment.body ?? "").includes(marker));
    const result = existing
      ? await this.request("PATCH", `/issues/comments/${existing.id}`, { body })
      : await this.comment(number, body);
    return result.id;
  }

  async upsertFollowUpComment(number, { pr, readyLabel }) {
    const marker = `<!-- social-drive-follow-up:pr-${pr}:issue-${number} -->`;
    const body = `${marker}\nDependency PR #${pr} merged after Discord approval. Added \`${readyLabel}\` so autonomous work can continue.`;
    const comments = await this.listAllIssueComments(number);
    const existing = comments.find((comment) => String(comment.body ?? "").includes(marker));
    const result = existing
      ? await this.request("PATCH", `/issues/comments/${existing.id}`, { body })
      : await this.comment(number, body);
    return result.id;
  }

  async reconcileControlLabels(number, wanted, managed) {
    const labels = await this.request("GET", `/issues/${number}/labels?per_page=100`);
    const current = new Set(labels.map((label) => label.name));
    const desired = new Set(wanted);
    const add = [...desired].filter((label) => !current.has(label));
    if (add.length) await this.request("POST", `/issues/${number}/labels`, { labels: add });
    for (const label of managed) {
      if (current.has(label) && !desired.has(label)) {
        await this.request("DELETE", `/issues/${number}/labels/${encodeURIComponent(label)}`);
      }
    }
  }

  async syncProjectSnapshot(snapshot) {
    if (!this.config.githubProjectId) return { skipped: true };
    let issueNodeId = snapshot.issueNodeId;
    if (!issueNodeId) issueNodeId = (await this.request("GET", `/issues/${snapshot.issue}`)).node_id;
    const data = await this.graphql(`query ControlCenterProject($id: ID!) {
      node(id: $id) { ... on ProjectV2 {
        fields(first: 50) { nodes {
          ... on ProjectV2FieldCommon { id name dataType }
          ... on ProjectV2SingleSelectField { id name dataType options { id name } }
        } }
        items(first: 100) { nodes { id content { ... on Issue { id } } } }
      } }
    }`, { id: this.config.githubProjectId });
    const project = data.node;
    if (!project) throw new Error("GitHub control center project is unavailable");
    let itemId = project.items.nodes.find((item) => item.content?.id === issueNodeId)?.id;
    if (!itemId) {
      const added = await this.graphql(`mutation AddControlCenterItem($projectId: ID!, $contentId: ID!) {
        addProjectV2ItemById(input: { projectId: $projectId, contentId: $contentId }) { item { id } }
      }`, { projectId: this.config.githubProjectId, contentId: issueNodeId });
      itemId = added.addProjectV2ItemById.item.id;
    }
    const values = {
      Status: snapshot.status,
      Phase: snapshot.phase,
      Health: snapshot.health,
      "Current owner": snapshot.persona,
      Progress: snapshot.progress,
      Tokens: snapshot.tokens,
      Attempt: snapshot.attempts,
      "Review round": snapshot.reviewRound,
      "Approval type": snapshot.approvalType,
      "Approval expires": snapshot.approvalExpiresAt?.slice(0, 10) ?? null,
      "Last activity": snapshot.updatedAt.slice(0, 10),
      "Next action": snapshot.nextAction,
    };
    for (const [name, value] of Object.entries(values)) {
      const field = project.fields.nodes.find((candidate) => candidate.name === name);
      if (!field) continue;
      if (value === null || value === "") {
        await this.graphql(`mutation ClearControlCenterField($input: ClearProjectV2ItemFieldValueInput!) {
          clearProjectV2ItemFieldValue(input: $input) { projectV2Item { id } }
        }`, { input: { projectId: this.config.githubProjectId, itemId, fieldId: field.id } });
        continue;
      }
      let fieldValue;
      if (field.dataType === "SINGLE_SELECT") {
        const optionId = field.options?.find((option) => option.name === value)?.id;
        if (!optionId) continue;
        fieldValue = { singleSelectOptionId: optionId };
      } else if (field.dataType === "NUMBER") fieldValue = { number: Number(value) };
      else if (field.dataType === "DATE") fieldValue = { date: String(value) };
      else fieldValue = { text: String(value).slice(0, 1024) };
      await this.graphql(`mutation UpdateControlCenterField($input: UpdateProjectV2ItemFieldValueInput!) {
        updateProjectV2ItemFieldValue(input: $input) { projectV2Item { id } }
      }`, { input: { projectId: this.config.githubProjectId, itemId, fieldId: field.id, value: fieldValue } });
    }
    return { skipped: false, itemId };
  }
  addReadyLabel(number) { return this.request("POST", `/issues/${number}/labels`, { labels: [this.config.readyLabel] }); }
  removeReadyLabel(number) { return this.request("DELETE", `/issues/${number}/labels/${encodeURIComponent(this.config.readyLabel)}`); }
  createPullRequest(head, title, body) { return this.request("POST", "/pulls", { head, base: this.config.baseBranch, title, body, draft: false }); }
  mergePullRequest(number, sha) {
    if (!/^[a-f0-9]{40}$/.test(String(sha ?? ""))) throw new Error("Merge approval is missing an exact commit SHA");
    return this.request("PUT", `/pulls/${number}/merge`, { merge_method: "squash", sha });
  }
  listPullRequestFiles(number) { return this.request("GET", `/pulls/${number}/files?per_page=100`); }
}

