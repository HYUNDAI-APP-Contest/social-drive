import { CONTROL_CENTER_SCHEMA, managedControlLabels } from "./control-center.mjs";

const optionColors = ["GRAY", "BLUE", "GREEN", "YELLOW", "RED", "PURPLE", "ORANGE"];
const optionInputs = (options, existing = []) => options.map((name, index) => ({
  ...(existing.find((option) => option.name === name)?.id ? { id: existing.find((option) => option.name === name).id } : {}),
  name,
  color: optionColors[index % optionColors.length],
  description: `${name} control-center value`,
}));

export const CONTROL_LABELS = Object.freeze(managedControlLabels().map((name) => ({
  name,
  color: name.includes(":blocked") ? "d73a4a" : name.includes(":approval") ? "fbca04" : name.includes(":done") ? "0e8a16" : "1d76db",
  description: "Managed by Social Drive development control center",
})));

export function controlCenterPlan(current) {
  const actions = [];
  if (!current.project) actions.push({ type: "create-project", title: CONTROL_CENTER_SCHEMA.title });
  else if (current.project.public) actions.push({ type: "set-private", projectId: current.project.id });
  const fields = current.project?.fields ?? [];
  for (const desired of CONTROL_CENTER_SCHEMA.fields) {
    const existing = fields.find((field) => field.name === desired.name);
    if (!existing) actions.push({ type: "create-field", field: desired });
    else if (desired.options) {
      const actual = (existing.options ?? []).map((option) => option.name);
      if (JSON.stringify(actual) !== JSON.stringify(desired.options)) actions.push({ type: "update-options", fieldId: existing.id, field: desired, existingOptions: existing.options ?? [] });
    }
  }
  const views = current.project?.views ?? [];
  for (const desired of CONTROL_CENTER_SCHEMA.views) {
    const existing = views.find((view) => view.name === desired.name);
    if (!existing) actions.push({ type: "create-view", view: desired });
    else if (existing.layout !== desired.layout || existing.filter !== desired.filter || JSON.stringify(existing.fields ?? []) !== JSON.stringify(desired.fields)) {
      actions.push({ type: "update-view", viewId: existing.id, view: desired });
    }
  }
  const labels = new Set(current.labels ?? []);
  for (const label of CONTROL_LABELS) if (!labels.has(label.name)) actions.push({ type: "create-label", label });
  return actions;
}

export class ProjectAdmin {
  constructor({ github, owner, repo }) {
    this.github = github;
    this.owner = owner;
    this.repo = repo;
  }

  async inspect() {
    const data = await this.github.graphql(`query InspectControlCenter($owner: String!) {
      organization(login: $owner) {
        id
        projectsV2(first: 100) { nodes {
          id number title url public
          fields(first: 50) { nodes {
            ... on ProjectV2Field { id name dataType }
            ... on ProjectV2SingleSelectField { id name dataType options { id name } }
          } }
          views(first: 50) { nodes {
            id name layout filter
            fields(first: 50) { nodes {
              ... on ProjectV2Field { name }
              ... on ProjectV2SingleSelectField { name }
            } }
          } }
        } }
      }
    }`, { owner: this.owner });
    if (!data.organization) throw new Error("GitHub organization is unavailable or the token lacks Projects read access");
    const project = data.organization.projectsV2.nodes.find((item) => item.title === CONTROL_CENTER_SCHEMA.title) ?? null;
    const labels = await this.github.request("GET", "/labels?per_page=100");
    return {
      ownerId: data.organization.id,
      project: project ? {
        ...project,
        fields: project.fields.nodes,
        views: project.views.nodes.map((view) => ({ ...view, fields: view.fields.nodes.map((field) => field.name) })),
      } : null,
      labels: labels.map((label) => label.name),
    };
  }

  async applyAction(action, context) {
    if (action.type === "create-project") {
      const data = await this.github.graphql(`mutation CreateControlCenter($ownerId: ID!, $title: String!) {
        createProjectV2(input: { ownerId: $ownerId, title: $title }) { projectV2 { id number title url public } }
      }`, { ownerId: context.ownerId, title: action.title });
      return data.createProjectV2.projectV2;
    }
    if (action.type === "set-private") {
      await this.github.graphql(`mutation MakeControlCenterPrivate($projectId: ID!) {
        updateProjectV2(input: { projectId: $projectId, public: false }) { projectV2 { id } }
      }`, { projectId: action.projectId });
      return null;
    }
    if (action.type === "create-field") {
      const input = { projectId: context.project.id, name: action.field.name, dataType: action.field.dataType };
      if (action.field.options) input.singleSelectOptions = optionInputs(action.field.options);
      await this.github.graphql(`mutation CreateControlCenterField($input: CreateProjectV2FieldInput!) {
        createProjectV2Field(input: $input) { projectV2Field { ... on ProjectV2Field { id } ... on ProjectV2SingleSelectField { id } } }
      }`, { input });
      return null;
    }
    if (action.type === "update-options") {
      await this.github.graphql(`mutation UpdateControlCenterOptions($input: UpdateProjectV2FieldInput!) {
        updateProjectV2Field(input: $input) { projectV2Field { ... on ProjectV2Field { id } ... on ProjectV2SingleSelectField { id } } }
      }`, { input: { fieldId: action.fieldId, singleSelectOptions: optionInputs(action.field.options, action.existingOptions) } });
      return null;
    }
    if (["create-view", "update-view"].includes(action.type)) {
      const visibleFieldIds = action.view.fields.map((name) => context.project.fields.find((field) => field.name === name)?.id).filter(Boolean);
      if (visibleFieldIds.length !== action.view.fields.length) throw new Error(`Cannot configure view before all fields exist: ${action.view.name}`);
      if (action.type === "create-view") {
        const created = await this.github.graphql(`mutation CreateControlCenterView($input: CreateProjectV2ViewInput!) {
          createProjectV2View(input: $input) { projectV2View { id } }
        }`, { input: { projectId: context.project.id, name: action.view.name, layout: action.view.layout, configuration: { visibleFieldIds } } });
        if (action.view.filter) {
          await this.github.graphql(`mutation FilterControlCenterView($input: UpdateProjectV2ViewInput!) {
            updateProjectV2View(input: $input) { projectV2View { id } }
          }`, { input: { viewId: created.createProjectV2View.projectV2View.id, filter: action.view.filter } });
        }
      } else {
        await this.github.graphql(`mutation UpdateControlCenterView($input: UpdateProjectV2ViewInput!) {
          updateProjectV2View(input: $input) { projectV2View { id } }
        }`, { input: { viewId: action.viewId, layout: action.view.layout, filter: action.view.filter, configuration: { visibleFieldIds } } });
      }
      return null;
    }
    if (action.type === "create-label") {
      await this.github.request("POST", "/labels", action.label);
      return null;
    }
    throw new Error(`Unsupported setup action: ${action.type}`);
  }

  async apply() {
    let current = await this.inspect();
    let actions = controlCenterPlan(current);
    const create = actions.find((action) => action.type === "create-project");
    if (create) {
      await this.applyAction(create, current);
      current = await this.inspect();
      actions = controlCenterPlan(current);
    }
    for (const action of actions.filter((item) => !["create-view", "update-view"].includes(item.type))) await this.applyAction(action, current);
    current = await this.inspect();
    actions = controlCenterPlan(current);
    for (const action of actions.filter((item) => ["create-view", "update-view"].includes(item.type))) await this.applyAction(action, current);
    const verified = await this.inspect();
    return { current: verified, remaining: controlCenterPlan(verified) };
  }
}
