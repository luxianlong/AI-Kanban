import { memo, useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { api } from './api.js'
import Icon from './Icon.jsx'
import Markdown from './Markdown.jsx'
import { MaximizeButton } from './resultWindow.jsx'
import { relTime, runMeta } from './time.js'
import {
  NODE_H, NODE_W, descendantsOf, edgePath, inRunOrder, layoutGraph, reachableFrom,
} from './workflowGraph.js'
import {
  entryNodeIdOf, followedNode, mergeExecutions, nodesWithStaleInput, outputPredatesSettings,
} from './workflowLive.js'
import { paneColumns, readCollapsed, writeCollapsed } from './workflowPanes.js'
// The editor's working copy and its conversions to/from what the API stores —
// nodes carry their own `inputs` rather than a separate edge list, because that is
// how they are edited. See workflowDraft.js.
import {
  EFFORT_LEVELS, ULTRACODE, appendStep, blankDraft, blankStep, draftFingerprint, draftFrom,
  favoriteFromStep, favoriteNamed, newId, payloadFrom, stepFromFavorite,
} from './workflowDraft.js'

// The Workflows page: author a DAG of prompt tasks on the left, watch the runs it
// produced on the right. Both halves live on one page because they are two views of
// the same thing — you tweak a prompt, run it, read what came out, tweak again.
//
// The one idea the UI has to get across is that nodes DON'T share a conversation.
// Every node is a fresh session; the only thing that travels along an edge is the
// upstream node's output text, pasted into the next node's input. So the editor
// talks about "input from", and an execution shows each node's input next to its
// output rather than one merged transcript.

// How hard a step thinks. Said once, and shown wherever the level is: it is the one
// setting on a step that changes what the step costs without changing what it says it
// does, so the picker has to make "more" and "less" legible rather than just listing
// five words. "Default" is not a sixth level — it means no --effort flag at all, so
// the CLI decides (see EffortLevel.java).
export const EFFORT_HINT =
  'How hard this step thinks. Higher levels reason for longer and cost more; leave it '
  + 'on Default unless a step is either the one doing the hard thinking or the one just '
  + 'tidying up what it was handed.'

// Said separately because ultracode is a separate thing, and a picker that lists it
// under the same heading as the five would be claiming it is the notch above max.
export const ULTRACODE_HINT =
  'Ultracode is not one step above max. It is xhigh thinking plus sub-sessions this '
  + 'step can hand work out to, so it changes how many sessions the step is — the '
  + 'most expensive option here by some way, and the slowest, since the step waits for '
  + 'that work. It is given several times the usual time limit for exactly that reason. '
  + 'Where ultracode is unavailable the step fails rather than quietly running at the '
  + 'default.'

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function WorkflowsPage({ onBack }) {
  const [workflows, setWorkflows] = useState([])
  const [executions, setExecutions] = useState([])
  // Steps kept aside to be used again. One shelf for the whole page, not per
  // workflow — the point of a favourite is that it crosses from one graph to another.
  const [favorites, setFavorites] = useState([])
  const [selectedId, setSelectedId] = useState(null)
  const [draft, setDraft] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  // Scope of the executions panel: the open workflow's runs, or every run.
  const [scopeAll, setScopeAll] = useState(false)
  // Which of the three panes are folded to a spine. Remembered per browser: on a
  // laptop the graph needs the whole window, and re-folding the list and the runs
  // on every visit is the kind of chore that makes people stop folding them.
  const [collapsed, setCollapsed] = useState(readCollapsed)
  const togglePane = (key) => setCollapsed((c) => {
    const next = { ...c, [key]: !c[key] }
    writeCollapsed(next)
    return next
  })

  const loadWorkflows = useCallback(async () => {
    const list = await api.listWorkflows()
    setWorkflows(list)
    return list
  }, [])

  const loadFavorites = useCallback(async () => {
    const list = await api.listFavoriteSteps()
    setFavorites(list)
    return list
  }, [])

  const loadExecutions = useCallback(async () => {
    try {
      const list = await api.listExecutions()
      // Fold the poll into what is on screen rather than replacing it: an unchanged
      // run keeps its object, and a memo'd card then skips re-rendering altogether.
      // Reading a step's output is the slowest thing anyone does here, and a run
      // finishing elsewhere has no business disturbing it. See workflowLive.js.
      setExecutions((prev) => mergeExecutions(prev, list))
    } catch (e) {
      setError(e.message)
    }
  }, [])

  // First load: show the newest workflow, so the page never opens on nothing.
  useEffect(() => {
    ;(async () => {
      try {
        const list = await loadWorkflows()
        if (list.length) {
          setSelectedId(list[0].id)
          setDraft(draftFrom(list[0]))
        }
        await loadExecutions()
        await loadFavorites()
      } catch (e) {
        setError(e.message)
      }
    })()
  }, [loadWorkflows, loadExecutions, loadFavorites])

  // Runs advance on the server, so poll while any of them is still going. Once
  // everything is finished the page is static and the poll costs a request every
  // 8s just to notice a run someone started elsewhere.
  const anyLive = executions.some((e) => e.status === 'PENDING' || e.status === 'RUNNING')
  useEffect(() => {
    const t = setInterval(loadExecutions, anyLive ? 2500 : 8000)
    return () => clearInterval(t)
  }, [loadExecutions, anyLive])

  const selectWorkflow = (workflow) => {
    setSelectedId(workflow.id)
    setDraft(draftFrom(workflow))
    setError(null)
  }

  const startNew = () => {
    setSelectedId(null)
    setDraft(blankDraft())
    setError(null)
  }

  async function save() {
    if (!draft) return
    setBusy(true)
    setError(null)
    try {
      const body = payloadFrom(draft)
      const saved = draft.id
        ? await api.saveWorkflow(draft.id, body)
        : await api.createWorkflow(body)
      await loadWorkflows()
      setSelectedId(saved.id)
      setDraft(draftFrom(saved))
    } catch (e) {
      setError(cleanError(e))
    } finally {
      setBusy(false)
    }
  }

  async function run(input) {
    if (!draft?.id) return
    setBusy(true)
    setError(null)
    try {
      await api.runWorkflow(draft.id, input || null)
      setScopeAll(false)
      await loadExecutions()
    } catch (e) {
      setError(cleanError(e))
    } finally {
      setBusy(false)
    }
  }

  /**
   * Copy the open workflow into a new one and open the copy — how a variant of a
   * working graph gets started without retyping every prompt.
   *
   * The server copies the STORED workflow, not the draft on screen: that way the
   * copy carries everything the row holds, including what the editor doesn't round-
   * trip. The cost is that unsaved edits wouldn't be in it, so say so first rather
   * than let them quietly go missing.
   */
  async function duplicate() {
    if (!draft?.id) return
    const saved = workflows.find((w) => w.id === draft.id)
    const unsaved = saved && draftFingerprint(draft) !== draftFingerprint(draftFrom(saved))
    if (unsaved && !window.confirm(
      `"${saved.name}" has unsaved edits. The copy is made from the last saved version, `
      + 'so those edits will not be in it. Duplicate anyway?')) return
    setBusy(true)
    setError(null)
    try {
      const copy = await api.duplicateWorkflow(draft.id)
      await loadWorkflows()
      // Open the copy: renaming it is the next thing anyone does.
      setSelectedId(copy.id)
      setDraft(draftFrom(copy))
    } catch (e) {
      setError(cleanError(e))
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    if (!draft?.id) return
    const runs = executions.filter((e) => e.workflowId === draft.id).length
    const warning = runs
      ? `Delete "${draft.name}" and its ${runs} run${runs > 1 ? 's' : ''}?`
      : `Delete "${draft.name}"?`
    if (!window.confirm(warning)) return
    setBusy(true)
    try {
      await api.deleteWorkflow(draft.id)
      const list = await loadWorkflows()
      await loadExecutions()
      if (list.length) selectWorkflow(list[0])
      else {
        setSelectedId(null)
        setDraft(null)
      }
    } catch (e) {
      setError(cleanError(e))
    } finally {
      setBusy(false)
    }
  }

  /**
   * Keep a step for later, under its own name.
   *
   * The shelf is keyed by name, so saving over one replaces it. That is nearly always
   * what is meant — you star a step, edit it, star it again — but it is also the one
   * way to lose a favourite without deleting it, so the case where a DIFFERENT step
   * has taken the name asks first. The check is here rather than on the server because
   * this is the side that knows the names: it renders them.
   */
  async function saveFavorite(node) {
    const step = favoriteFromStep(node)
    const existing = favoriteNamed(favorites, step.name)
    if (existing && existing.prompt !== step.prompt && !window.confirm(
      `A favourite step called "${existing.name}" already exists. `
      + "Replace it with this step's prompt?")) return
    setError(null)
    try {
      await api.saveFavoriteStep(step)
      await loadFavorites()
    } catch (e) {
      setError(cleanError(e))
    }
  }

  async function removeFavorite(favorite) {
    if (!window.confirm(
      `Remove "${favorite.name}" from your favourite steps? `
      + 'Workflows built from it keep their own copy.')) return
    setError(null)
    try {
      await api.deleteFavoriteStep(favorite.id)
      await loadFavorites()
    } catch (e) {
      setError(cleanError(e))
    }
  }

  const shown = scopeAll || !selectedId
    ? executions
    : executions.filter((e) => e.workflowId === selectedId)

  return (
    <div className="page wf-page">
      <header className="topbar">
        <div className="brand">
          <button type="button" className="wf-back" onClick={onBack} title="Back to the board">
            <Icon name="back" size={18} />
          </button>
          <div>
            <h1>Workflows</h1>
            <p className="tagline">
              Chain prompts into a graph. Each step runs as its own session and passes
              its answer to the next.
            </p>
          </div>
        </div>
      </header>

      {error && <div className="error"><Icon name="alert" size={15} /> {error}</div>}

      <div className="wf-body" style={bodyColumns(collapsed)}>
        <aside className={paneClass('wf-rail', collapsed.list)}>
          {collapsed.list ? (
            <PaneStrip
              label="Workflows"
              hint={countLabel(workflows.length, 'workflow')}
              dir="right"
              onClick={() => togglePane('list')}
            />
          ) : (
          <>
          <div className="wf-rail-head">
            <span className="section-label">Workflows</span>
            <div className="wf-head-actions">
              <button type="button" className="btn ghost wf-new" onClick={startNew}>
                <Icon name="plus" size={14} /> New
              </button>
              <PaneHide label="Workflows" dir="left" onClick={() => togglePane('list')} />
            </div>
          </div>
          {workflows.length === 0 && !draft && (
            <p className="wf-empty">No workflows yet. Create one to get started.</p>
          )}
          <ul className="wf-list">
            {workflows.map((w) => (
              <li key={w.id}>
                <button
                  type="button"
                  className={`wf-list-item ${w.id === selectedId ? 'on' : ''}`}
                  onClick={() => selectWorkflow(w)}
                >
                  <span className="wf-list-name">{w.name}</span>
                  <span className="wf-list-meta">
                    {w.nodes.length} step{w.nodes.length === 1 ? '' : 's'}
                  </span>
                </button>
              </li>
            ))}
            {draft && !draft.id && (
              <li>
                <span className="wf-list-item on wf-list-draft">
                  <span className="wf-list-name">{draft.name || 'Untitled workflow'}</span>
                  <span className="wf-list-meta">unsaved</span>
                </span>
              </li>
            )}
          </ul>
          </>
          )}
        </aside>

        <main className={paneClass('wf-editor-pane', collapsed.editor)}>
          {collapsed.editor ? (
            <PaneStrip
              label="Editor"
              hint={draft ? draft.name || 'Untitled workflow' : null}
              dir="right"
              onClick={() => togglePane('editor')}
            />
          ) : (
          <>
          <div className="wf-rail-head wf-editor-head">
            <span className="section-label">
              {draft ? (draft.id ? 'Editing' : 'New workflow') : 'Editor'}
            </span>
            <PaneHide label="the editor" dir="left" onClick={() => togglePane('editor')} />
          </div>
          {draft ? (
            <WorkflowEditor
              draft={draft}
              onChange={setDraft}
              onSave={save}
              onRun={run}
              onDuplicate={draft.id ? duplicate : null}
              onDelete={draft.id ? remove : null}
              favorites={favorites}
              onSaveFavorite={saveFavorite}
              onRemoveFavorite={removeFavorite}
              busy={busy}
            />
          ) : (
            <div className="wf-blank">
              <Icon name="workflow" size={28} />
              <h2>Build a workflow</h2>
              <p>
                A workflow is a graph of prompts. The first step gets whatever you type
                when you start a run; every step after it gets the previous step's answer
                as its input, plus its own instructions.
              </p>
              <button type="button" className="btn primary" onClick={startNew}>
                New workflow
              </button>
            </div>
          )}
          </>
          )}
        </main>

        <aside className={paneClass('wf-runs-pane', collapsed.runs)}>
          {collapsed.runs ? (
            <PaneStrip
              label="Executions"
              hint={countLabel(shown.length, 'run')}
              dir="left"
              onClick={() => togglePane('runs')}
            />
          ) : (
          <>
          <div className="wf-rail-head">
            <span className="section-label">Executions</span>
            <div className="wf-head-actions">
            <div className="wf-scope">
              <button
                type="button"
                className={`wf-scope-btn ${!scopeAll ? 'on' : ''}`}
                onClick={() => setScopeAll(false)}
                disabled={!selectedId}
              >
                This one
              </button>
              <button
                type="button"
                className={`wf-scope-btn ${scopeAll ? 'on' : ''}`}
                onClick={() => setScopeAll(true)}
              >
                All
              </button>
            </div>
            <PaneHide label="Executions" dir="right" onClick={() => togglePane('runs')} />
            </div>
          </div>
          {shown.length === 0 ? (
            <p className="wf-empty">
              No runs yet. Save a workflow and press Run to start one.
            </p>
          ) : (
            <ul className="wf-runs">
              {shown.map((execution) => (
                <ExecutionCard
                  key={execution.id}
                  execution={execution}
                  showWorkflowName={scopeAll || !selectedId}
                  onChanged={loadExecutions}
                />
              ))}
            </ul>
          )}
          </>
          )}
        </aside>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Folding a pane
// ---------------------------------------------------------------------------
// A folded pane leaves a spine behind rather than vanishing: an empty gutter gives
// you nothing to click, and no way to tell that something is hidden from something
// that was never there. The spine says what it is and how much of it there is.

const paneClass = (base, isCollapsed) =>
  `wf-pane ${base}${isCollapsed ? ' collapsed' : ''}`

/**
 * Grid tracks as custom properties rather than `grid-template-columns` directly:
 * an inline shorthand would beat the stacked layout's media query, and the panes
 * would stay in three columns on a phone.
 */
function bodyColumns(collapsed) {
  const [list, editor, runs] = paneColumns(collapsed)
  return { '--wf-col-list': list, '--wf-col-editor': editor, '--wf-col-runs': runs }
}

const countLabel = (n, noun) => (n ? `${n} ${noun}${n === 1 ? '' : 's'}` : `no ${noun}s`)

/** The folded pane: the whole spine is the button that brings it back. */
function PaneStrip({ label, hint, dir, onClick }) {
  return (
    <button
      type="button"
      className="wf-pane-strip"
      onClick={onClick}
      aria-expanded={false}
      aria-label={`Show ${label}`}
      title={`Show ${label}`}
    >
      <Icon name="chevron" size={14} className={`wf-chev ${dir}`} />
      <span className="wf-pane-strip-label">{label}</span>
      {hint && <span className="wf-pane-strip-hint">{hint}</span>}
    </button>
  )
}

/** The fold control, sitting in an open pane's header. */
function PaneHide({ label, dir, onClick }) {
  return (
    <button
      type="button"
      className="icon-btn wf-pane-hide"
      onClick={onClick}
      aria-expanded
      aria-label={`Hide ${label}`}
      title={`Hide ${label}`}
    >
      <Icon name="chevron" size={14} className={`wf-chev ${dir}`} />
    </button>
  )
}

// ---------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------

function WorkflowEditor({
  draft, onChange, onSave, onRun, onDuplicate, onDelete,
  favorites, onSaveFavorite, onRemoveFavorite, busy,
}) {
  const [runInput, setRunInput] = useState('')
  // The graph is the editing surface: click a step in the picture and its card
  // opens underneath. Every prompt shown at once turned the page into a column of
  // textareas in which the shape of the graph — the thing being authored — was the
  // one thing you couldn't see.
  const [selectedId, setSelectedId] = useState(draft.entryNodeId)
  const update = (patch) => onChange({ ...draft, ...patch })

  const setNode = (id, patch) =>
    update({ nodes: draft.nodes.map((n) => (n.id === id ? { ...n, ...patch } : n)) })

  // Both ways of adding a step append it wired to the last one — see appendStep.
  // The new step is selected either way, so its card opens: on a blank one that is
  // where you type, and on a favourite it is how you see what you just inserted
  // (which may be off the right edge of a wide graph).
  const addNode = () => {
    const id = newId()
    setSelectedId(id)
    onChange(appendStep(draft, blankStep(id, draft.nodes.length)))
  }

  /**
   * Write a favourite into this graph as a new step. A copy, not a link: from here
   * on it is an ordinary step, editable without touching the shelf.
   */
  const insertFavorite = (favorite) => {
    const id = newId()
    setSelectedId(id)
    onChange(appendStep(draft, stepFromFavorite(favorite, id)))
  }

  const removeNode = (id) => {
    const nodes = draft.nodes
      .filter((n) => n.id !== id)
      .map((n) => ({ ...n, inputs: n.inputs.filter((from) => from !== id) }))
    const entryNodeId = draft.entryNodeId === id
      // Deleting the entry has to hand the role to something, or the workflow
      // becomes unsaveable without the user working out why.
      ? nodes.find((n) => !n.inputs.length)?.id ?? nodes[0]?.id
      : draft.entryNodeId
    if (selectedId === id) setSelectedId(entryNodeId)
    update({ nodes, entryNodeId })
  }

  const toggleInput = (nodeId, fromId) => {
    const node = draft.nodes.find((n) => n.id === nodeId)
    const inputs = node.inputs.includes(fromId)
      ? node.inputs.filter((i) => i !== fromId)
      : [...node.inputs, fromId]
    setNode(nodeId, { inputs })
  }

  const reachable = useMemo(
    () => reachableFrom(draft.nodes, draft.entryNodeId),
    [draft.nodes, draft.entryNodeId],
  )
  // Falls back rather than blanking: a draft can be replaced under us by a save
  // (ids survive) or by starting a second new workflow (they don't).
  const selected = draft.nodes.find((n) => n.id === selectedId)
    ?? draft.nodes.find((n) => n.id === draft.entryNodeId)
    ?? draft.nodes[0]
  const canSave = draft.name.trim().length > 0 && draft.nodes.length > 0 && !busy

  return (
    <div className="wf-editor">
      <input
        className="composer-title wf-name"
        placeholder="Workflow name"
        value={draft.name}
        onChange={(e) => update({ name: e.target.value })}
        maxLength={200}
      />
      <input
        className="composer-desc wf-desc"
        placeholder="What this workflow is for (optional)"
        value={draft.description}
        onChange={(e) => update({ description: e.target.value })}
        maxLength={2000}
      />

      <WorkflowGraph
        nodes={draft.nodes}
        entryNodeId={draft.entryNodeId}
        reachable={reachable}
        selectedId={selected?.id}
        onSelect={setSelectedId}
        label="Workflow graph — pick a step to edit it"
      />

      <div className="wf-graph-bar">
        <button type="button" className="btn ghost wf-add-node" onClick={addNode}>
          <Icon name="plus" size={14} /> Add step
        </button>
        <span className="wf-run-hint">Pick a step in the graph to edit it.</span>
      </div>

      {/* The shelf, only once there is something on it: a row explaining that you
          have no favourites is worse than no row, and the star that fills it is on
          every step's card anyway. */}
      {favorites.length > 0 && (
        <div className="wf-favorites">
          <span className="wf-inputs-label">Insert a favourite</span>
          {favorites.map((favorite) => (
            <span key={favorite.id} className="wf-favorite">
              <button
                type="button"
                className="wf-chip wf-favorite-insert"
                onClick={() => insertFavorite(favorite)}
                title={`Add "${favorite.name}" as a new step${
                  favorite.effort ? ` (${favorite.effort})` : ''}`}
              >
                <Icon name="star" size={11} className="wf-star-on" />
                {favorite.name}
              </button>
              <button
                type="button"
                className="wf-favorite-drop"
                onClick={() => onRemoveFavorite(favorite)}
                aria-label={`Remove "${favorite.name}" from favourite steps`}
                title={`Remove "${favorite.name}" from favourite steps`}
              >
                <Icon name="close" size={11} />
              </button>
            </span>
          ))}
        </div>
      )}

      {selected && (
        <NodeEditor
          key={selected.id}
          node={selected}
          index={draft.nodes.indexOf(selected)}
          all={draft.nodes}
          isEntry={selected.id === draft.entryNodeId}
          unreached={!reachable.has(selected.id)}
          onChange={(patch) => setNode(selected.id, patch)}
          onMakeEntry={() => update({ entryNodeId: selected.id })}
          onToggleInput={(fromId) => toggleInput(selected.id, fromId)}
          onRemove={draft.nodes.length > 1 ? () => removeNode(selected.id) : null}
          favorite={favoriteNamed(favorites, selected.name)}
          onFavorite={() => onSaveFavorite(selected)}
        />
      )}

      <div className="wf-editor-actions">
        <button type="button" className="btn primary" onClick={onSave} disabled={!canSave}>
          {draft.id ? 'Save changes' : 'Create workflow'}
        </button>
        {onDuplicate && (
          <button
            type="button"
            className="btn ghost"
            onClick={onDuplicate}
            disabled={busy}
            title="Create a copy of this workflow to edit separately"
          >
            <Icon name="copy" size={13} /> Duplicate
          </button>
        )}
        {onDelete && (
          <button type="button" className="btn danger-ghost" onClick={onDelete} disabled={busy}>
            Delete
          </button>
        )}
      </div>

      {draft.id && (
        <div className="wf-run-box">
          <span className="section-label">Start a run</span>
          <textarea
            className="composer-desc"
            rows={3}
            placeholder="Input for the first step — the subject, the question, the text to work on (optional)"
            value={runInput}
            onChange={(e) => setRunInput(e.target.value)}
            maxLength={20000}
          />
          <div className="composer-actions">
            <button
              type="button"
              className="btn primary"
              onClick={() => { onRun(runInput); setRunInput('') }}
              disabled={busy}
            >
              <Icon name="play" size={13} /> Run workflow
            </button>
            <span className="wf-run-hint">
              Save any edits first — a run uses the last saved version.
            </span>
          </div>
        </div>
      )}
    </div>
  )
}

/**
 * The "how hard does this step think" picker, with the sentence that explains a level
 * once it is set. One component because a step's effort is now chosen in two places —
 * in the editor, where it is what every future run does, and inside a finished run,
 * where it is an experiment costing one session — and two pickers offering different
 * levels, or explaining ultracode differently, would be a bug waiting to happen.
 */
function EffortPicker({ id, value, onChange, label = 'Effort' }) {
  return (
    <div className="wf-node-effort">
      <label className="wf-inputs-label" htmlFor={id}>{label}</label>
      <select
        id={id}
        className="wf-effort-select"
        value={value || ''}
        title={EFFORT_HINT}
        onChange={(e) => onChange(e.target.value)}
      >
        <option value="">Default</option>
        {/* Two groups rather than one list of six. Dropping ultracode in after 'max'
            would read as the next notch up the same scale, which is the one thing
            about it that is wrong. */}
        <optgroup label="Think harder">
          {EFFORT_LEVELS.map((level) => (
            <option key={level} value={level}>{level}</option>
          ))}
        </optgroup>
        <optgroup label="Think harder and fan out">
          <option value={ULTRACODE} title={ULTRACODE_HINT}>{ULTRACODE}</option>
        </optgroup>
      </select>
      {/* Only when it is set: a line under every step saying "this is normal" is
          noise, and the point of the label is that this step is not. */}
      {value && (
        <span className="wf-effort-note">
          {value === ULTRACODE
            ? 'Runs at ultracode: xhigh thinking plus sub-sessions it can hand work '
              + 'out to, with several times the usual time limit because it waits for '
              + 'them. The most expensive level, and it fails outright where '
              + 'ultracode is unavailable rather than falling back to the default.'
            : `Runs at ${value} instead of the default.`}
        </span>
      )}
    </div>
  )
}

function NodeEditor({
  node, index, all, isEntry, unreached, onChange, onMakeEntry, onToggleInput, onRemove,
  favorite, onFavorite,
}) {
  // Anything downstream of this node would close a loop, so it isn't offered.
  const blocked = useMemo(() => descendantsOf(all, node.id), [all, node.id])
  const candidates = all.filter((n) => n.id !== node.id && !blocked.has(n.id))

  return (
    <div className={`wf-node ${isEntry ? 'entry' : ''} ${unreached ? 'unreached' : ''}`}>
      <div className="wf-node-head">
        <span className="wf-node-index">{index + 1}</span>
        <input
          className="wf-node-name"
          value={node.name}
          placeholder="Step name"
          onChange={(e) => onChange({ name: e.target.value })}
          maxLength={200}
        />
        {node.effort && (
          <span className="wf-badge effort-badge" title={EFFORT_HINT}>{node.effort}</span>
        )}
        {isEntry ? (
          <span className="wf-badge entry-badge" title="Every run starts here">Entry</span>
        ) : (
          <button type="button" className="wf-make-entry" onClick={onMakeEntry}>
            Make entry
          </button>
        )}
        {/* Keep this step to use again. Filled once it is on the shelf under this
            name — and still pressable then, because re-saving an edited step is the
            commonest reason to press it a second time. A step with no prompt has
            nothing worth keeping, so the star waits for one rather than saving a
            favourite that inserts as blank. */}
        <button
          type="button"
          className={`icon-btn wf-favorite-btn ${favorite ? 'on' : ''}`}
          onClick={onFavorite}
          disabled={!node.prompt.trim()}
          aria-pressed={!!favorite}
          aria-label={favorite
            ? `Update the favourite step "${favorite.name}" from this step`
            : `Save "${node.name || `step ${index + 1}`}" as a favourite step`}
          title={node.prompt.trim()
            ? (favorite
              ? `Saved as a favourite. Press again to update "${favorite.name}" `
                + 'with this step\'s prompt.'
              : 'Keep this step as a favourite, to insert into any workflow.')
            : 'Write a prompt first — a favourite is the prompt, not the box.'}
        >
          <Icon name="star" size={15} className={favorite ? 'wf-star-on' : undefined} />
        </button>
        {onRemove && (
          <button
            type="button"
            className="icon-btn danger"
            onClick={onRemove}
            aria-label={`Remove step ${node.name || index + 1}`}
          >
            <Icon name="delete" size={15} />
          </button>
        )}
      </div>

      <textarea
        className="composer-desc wf-node-prompt"
        rows={4}
        placeholder={
          isEntry
            ? 'What this first step should do with the input you give the run'
            : "What to do with the previous step's answer"
        }
        value={node.prompt}
        onChange={(e) => onChange({ prompt: e.target.value })}
        maxLength={20000}
      />

      <EffortPicker
        id={`effort-${node.id}`}
        value={node.effort}
        onChange={(effort) => onChange({ effort })}
      />

      <div className="wf-inputs">
        <span className="wf-inputs-label">Input from</span>
        {candidates.length === 0 ? (
          <span className="wf-inputs-none">
            {isEntry ? 'whatever you type when starting the run' : 'nothing yet'}
          </span>
        ) : (
          candidates.map((other) => (
            <button
              key={other.id}
              type="button"
              className={`wf-chip ${node.inputs.includes(other.id) ? 'on' : ''}`}
              onClick={() => onToggleInput(other.id)}
            >
              {other.name || 'Untitled step'}
            </button>
          ))
        )}
      </div>

      {unreached && (
        <p className="wf-node-warn">
          Nothing leads here from the entry step, so a run will skip it.
        </p>
      )}
    </div>
  )
}

/** How far the picture may shrink before the labels stop being words. */
const MIN_SCALE = 0.6

/**
 * The DAG itself: one column per depth, curved edges with arrowheads, drawn from
 * the layout in workflowGraph.js. This is the page's main view of a workflow, in
 * the editor and in a run alike — a run passes `statuses` and gets the same boxes
 * coloured by how each step went.
 *
 * Nodes are selectable (click, or Enter/Space when focused); edges are not drawn
 * or dragged here — connections stay on the "Input from" chips, where a wrong one
 * is a mis-click rather than a mis-aimed line.
 *
 * The picture shrinks to whatever width it is given rather than making a narrow
 * window scroll at once: the layout keeps its own units and only the rendered size
 * changes. It stops shrinking at MIN_SCALE, below which the labels stop being
 * words, and scrolls sideways from there.
 */
function WorkflowGraph({
  nodes, entryNodeId, reachable, selectedId, onSelect, statuses, label,
}) {
  const layout = useMemo(() => layoutGraph(nodes), [nodes])
  // The number on a box is the step's place in run order, which is what the
  // picture is arranged by — not the order the steps happen to be stored in.
  const order = useMemo(
    () => new Map(inRunOrder(nodes).map((n, index) => [n.id, index + 1])),
    [nodes],
  )
  // One marker per instance: several graphs can be on screen at once (each open
  // run has one) and duplicate SVG ids resolve to whichever came first.
  const arrow = `wf-arrow-${useId().replace(/[^a-zA-Z0-9]/g, '')}`

  // Fit the drawing to the room it has. clientWidth is the scroll box's own padding
  // box, so it stays the width of the space available however wide the SVG inside it
  // grows — scrollWidth is the one that would report the graph back to us.
  const boxRef = useRef(null)
  const [scale, setScale] = useState(1)
  useEffect(() => {
    const el = boxRef.current
    if (!el) return undefined
    const fit = () => {
      const pad = getComputedStyle(el)
      const room = el.clientWidth
        - (parseFloat(pad.paddingLeft) || 0) - (parseFloat(pad.paddingRight) || 0)
      if (room > 0) setScale(Math.min(1, Math.max(MIN_SCALE, room / layout.width)))
    }
    fit()
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', fit)
      return () => window.removeEventListener('resize', fit)
    }
    const observer = new ResizeObserver(fit)
    observer.observe(el)
    return () => observer.disconnect()
  }, [layout.width])

  // Still too wide to fit at MIN_SCALE: it scrolls sideways, and "Add step" selects
  // the step it just made — which would otherwise appear off the right edge and
  // look like nothing happened.
  //
  // Only ever on a *change* of selection or scale, though. `layout` is a fresh
  // object on every poll of a live run, so running this whenever it changes identity
  // would smooth-scroll the graph back every 2.5 seconds — undoing the scroll of
  // anyone reading the far end of a wide run.
  const broughtIntoView = useRef({ id: null, scale: 0 })
  useEffect(() => {
    const box = boxRef.current
    const at = selectedId && layout.positions.get(selectedId)
    if (!box || !at) return
    const done = broughtIntoView.current
    if (done.id === selectedId && done.scale === scale) return
    broughtIntoView.current = { id: selectedId, scale }
    const left = at.x * scale - 16
    const right = (at.x + NODE_W) * scale + 16
    if (left < box.scrollLeft) box.scrollTo({ left, behavior: 'smooth' })
    else if (right > box.scrollLeft + box.clientWidth) {
      box.scrollTo({ left: right - box.clientWidth, behavior: 'smooth' })
    }
  }, [selectedId, layout, scale])

  if (!nodes.length) return null

  return (
    <div className="wf-graph" ref={boxRef}>
      <svg
        viewBox={`0 0 ${layout.width} ${layout.height}`}
        width={Math.round(layout.width * scale)}
        height={Math.round(layout.height * scale)}
        role="img"
        aria-label={label || 'Workflow graph'}
      >
        <defs>
          <marker
            id={arrow}
            viewBox="0 0 8 8"
            refX="7"
            refY="4"
            markerWidth="7"
            markerHeight="7"
            orient="auto-start-reverse"
          >
            <path className="wf-arrowhead" d="M0 0 L8 4 L0 8 z" />
          </marker>
        </defs>

        {/* Edges last-to-first would hide the highlighted one under its neighbours,
            so the ones touching the selected step are drawn after the rest. */}
        {[...layout.edges]
          .sort((a, b) => Number(touches(a, selectedId)) - Number(touches(b, selectedId)))
          .map((edge) => (
            <path
              key={`${edge.from}-${edge.to}`}
              className={`wf-edge ${touches(edge, selectedId) ? 'on' : ''}`}
              markerEnd={`url(#${arrow})`}
              d={edgePath(edge.points)}
            />
          ))}

        {nodes.map((node) => {
          const at = layout.positions.get(node.id)
          if (!at) return null
          const isEntry = node.id === entryNodeId
          const status = statuses?.get(node.id)
          const subtitle = status
            ? stateLabel(status)
            : (isEntry ? 'Entry' : (reachable && !reachable.has(node.id) ? 'Unreached' : null))
          const classes = [
            'wf-gnode',
            isEntry ? 'entry' : '',
            reachable && !reachable.has(node.id) ? 'unreached' : '',
            selectedId === node.id ? 'on' : '',
            status ? status.toLowerCase() : '',
            onSelect ? 'pickable' : '',
          ].filter(Boolean).join(' ')
          return (
            <g
              key={node.id}
              className={classes}
              role={onSelect ? 'button' : undefined}
              tabIndex={onSelect ? 0 : undefined}
              aria-pressed={onSelect ? selectedId === node.id : undefined}
              aria-label={onSelect ? `Step ${order.get(node.id)}: ${node.name || 'Untitled'}` : undefined}
              onClick={onSelect ? () => onSelect(node.id) : undefined}
              onKeyDown={onSelect ? (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  onSelect(node.id)
                }
              } : undefined}
            >
              {/* A halo rather than a heavier border: the border already carries
                  "entry", and a status fill already carries how the step went. */}
              {selectedId === node.id && (
                <rect
                  className="wf-gnode-ring"
                  x={at.x - 4}
                  y={at.y - 4}
                  width={NODE_W + 8}
                  height={NODE_H + 8}
                  rx="13"
                />
              )}
              <rect x={at.x} y={at.y} width={NODE_W} height={NODE_H} rx="9" />
              <text className="wf-gnode-num" x={at.x + 12} y={at.y + (subtitle ? 20 : 28)}>
                {order.get(node.id)}
              </text>
              <text className="wf-gnode-name" x={at.x + 28} y={at.y + (subtitle ? 20 : 28)}>
                {truncate(node.name || 'Untitled', 16)}
              </text>
              {subtitle && (
                <text className="wf-gnode-sub" x={at.x + 28} y={at.y + 34}>
                  {subtitle}
                </text>
              )}
            </g>
          )
        })}
      </svg>
    </div>
  )
}

const touches = (edge, nodeId) => !!nodeId && (edge.from === nodeId || edge.to === nodeId)

// ---------------------------------------------------------------------------
// Executions
// ---------------------------------------------------------------------------

const ExecutionCard = memo(function ExecutionCard({ execution, showWorkflowName, onChanged }) {
  const [open, setOpen] = useState(false)
  // Which step's detail is expanded. A run's picture is the same graph the editor
  // draws, so picking a box here means the same thing it does there: show me this
  // step. The list underneath stays — it is where the input, output and log live.
  const [openNodeId, setOpenNodeId] = useState(null)
  // Whether the card is still following the run. See the effect below.
  const [following, setFollowing] = useState(true)
  const [retrying, setRetrying] = useState(false)
  const live = execution.status === 'PENDING' || execution.status === 'RUNNING'

  // Picking a step is how you say "this is the one I'm reading" — so it also stops
  // the card following the run. Toggling the same step shut is still a deliberate
  // pick, and following stays off; otherwise closing a panel would hand control
  // back to the poll, which is the opposite of what closing it meant.
  const pickNode = (id) => {
    setFollowing(false)
    setOpenNodeId((current) => (current === id ? null : id))
  }

  const graphNodes = useMemo(
    () => execution.nodes.map((n) => ({ id: n.nodeId, name: n.nodeName, inputs: n.dependsOn || [] })),
    [execution.nodes],
  )
  const statuses = useMemo(
    () => new Map(execution.nodes.map((n) => [n.nodeId, n.status])),
    [execution.nodes],
  )
  // Which steps a single-step re-run has left behind: their output is still there and
  // still says "Done", but the input it was written from has moved since. Derived for
  // the whole run at once because it is a question about the graph, not about a row.
  const staleInput = useMemo(() => nodesWithStaleInput(execution.nodes), [execution.nodes])
  // The step this run started from, as the run itself recorded it — it is drawn
  // differently, and marked as the entry in the step list.
  const entryNodeId = entryNodeIdOf(execution)

  // On a run you just started, whatever is running is what you want open — it is
  // the only step with anything to show yet. But once you have picked a step
  // yourself you are reading it, and a step finishing must not move the panel out
  // from under you. So following is sticky-off: it survives until the first
  // deliberate pick, and only the "Follow the run" button turns it back on.
  const runningNodeId = execution.nodes.find((n) => n.status === 'RUNNING')?.nodeId
  useEffect(() => {
    setOpenNodeId((current) => followedNode(current, runningNodeId, following))
  }, [runningNodeId, following])

  async function cancel(e) {
    e.stopPropagation()
    try {
      await api.cancelExecution(execution.id)
      onChanged()
    } catch {
      /* the poll will show whatever really happened */
    }
  }

  // Retrying is queueing the same run again from the step that broke — which is
  // why the button says how many steps that is. Starting the whole workflow over
  // is the other button, on the editor; the difference between them is money.
  async function retry(e) {
    e.stopPropagation()
    setRetrying(true)
    try {
      await api.retryExecution(execution.id)
      // The run is live again and the step to watch is the one being redone, so
      // hand the card back to the run — the same state a fresh run starts in.
      setFollowing(true)
      onChanged()
    } catch {
      // A refusal here means the run moved since the page last looked at it — it
      // went live again, or someone else already retried it. The API says which in
      // a sentence the error response doesn't carry (see cleanError), so relaying
      // it would only show "409 Conflict". Re-read the run instead: its new state
      // is the answer, and it is what makes the button correct again.
      window.alert('This run has changed since the page last read it, so it was not '
        + 'retried. The runs list has been refreshed.')
      onChanged()
    } finally {
      setRetrying(false)
    }
  }

  // Re-running one step is the other question retrying answers. Retry asks the run
  // which steps need doing over; this asks the user, because a step can succeed and
  // still be wrong — and the answer costs a fresh session per step, so the count of
  // what goes with it is the thing to put in front of them.
  //
  // `downstream` is the choice between the two honest answers to "what else does this
  // invalidate". With it, the steps below are redone, which is correct and can cost
  // eight sessions to check one wording. Without it, one session — and the steps below
  // are left describing an output that has moved, which the panel then says out loud
  // rather than leaving to be discovered. Whichever the user picks, the confirm spells
  // out that half of the trade, because it is the half that isn't in the button.
  async function rerunFrom(run, downstream) {
    const also = run.rerunFromCount - 1
    const ok = window.confirm(
      downstream
        ? `Re-run "${run.nodeName}"${also ? ` and the ${plural(also, 'step')} after it` : ''}?\n\n`
          + `${also ? 'Those steps read this one\'s output, so what they produced is discarded and each ' : 'It '}`
          + `runs a fresh Claude session — real minutes and real money. The steps before `
          + `"${run.nodeName}" are untouched and keep their output.`
        : `Re-run only "${run.nodeName}"?\n\n`
          + `One fresh Claude session, and nothing else in this run is touched. The `
          + `${plural(also, 'step')} after it will keep what they produced — which was `
          + `written from this step's previous output, so they will be marked as reading `
          + `an input that has since changed. Re-run them too when the new output looks right.`,
    )
    if (!ok) return
    try {
      await api.retryNodeRun(run.id, { downstream })
      // Live again, and the step to watch is the one being redone.
      setFollowing(true)
      onChanged()
    } catch {
      // Same reasoning as retry(): a refusal means the run moved under the page.
      window.alert('This run has changed since the page last read it, so that step was '
        + 'not re-run. The runs list has been refreshed.')
      onChanged()
    }
  }

  // Editing a step inside the run rather than in the workflow. The run holds its own
  // copy of every step — prompt, effort and wiring — and that is what makes this a
  // cheap experiment: change one thing, re-run that one step, and the step is handed
  // the input it had before, so what moved is what you moved. The error is handed back
  // to the form rather than swallowed, because what the user typed is still in it.
  async function editStep(run, settings) {
    await api.editNodeRun(run.id, settings)
    onChanged()
  }

  // Adding a step the workflow never had. The other half of debugging: sometimes the
  // answer to "why did the last step come out like that" is a step that is missing —
  // a checking pass, a reformat — and until now finding out meant editing the graph
  // and paying for every step that already came out fine. It lands queued and starts
  // nothing; the re-run button on the new row is what spends a session.
  async function addStepAfter(run, step) {
    await api.insertNodeRun(execution.id, { ...step, inputs: [run.nodeId] })
    onChanged()
  }

  // Removing a step from the run. Unlike the two above this destroys what the step
  // produced, and it rewires the steps that read it onto whatever it read — so the
  // confirm spells out both, since neither is in the button.
  async function removeStep(run) {
    const reads = execution.nodes.filter((n) => (n.dependsOn || []).includes(run.nodeId))
    const ok = window.confirm(
      `Remove "${run.nodeName}" from this run?\n\n`
      + `Whatever it produced is deleted with it${run.costUsd != null
        ? ` (it cost $${run.costUsd.toFixed(4)})` : ''}. `
      + `${reads.length
        ? `${plural(reads.length, 'step')} that read it will read what it read instead, `
          + 'so their answers will be marked as written from an input that has changed. '
        : ''}`
      + 'The workflow itself keeps the step.',
    )
    if (!ok) return
    try {
      await api.deleteNodeRun(run.id)
      onChanged()
    } catch {
      window.alert('This run has changed since the page last read it, so that step was '
        + 'not removed. The runs list has been refreshed.')
      onChanged()
    }
  }

  async function remove(e) {
    e.stopPropagation()
    if (!window.confirm('Delete this run and everything it produced?')) return
    try {
      await api.deleteExecution(execution.id)
      onChanged()
    } catch {
      /* as above */
    }
  }

  return (
    <li className={`wf-run ${open ? 'open' : ''}`}>
      <button type="button" className="wf-run-head" onClick={() => setOpen((v) => !v)}>
        <span className={`wf-state ${execution.status.toLowerCase()}`}>{stateLabel(execution.status)}</span>
        <span className="wf-run-title">
          {showWorkflowName && <strong>{execution.workflowName} </strong>}
          <span className="wf-run-id">#{execution.id}</span>
        </span>
        <span className="wf-run-progress">{execution.doneNodes}/{execution.totalNodes}</span>
        <span className="wf-run-when">{relTime(execution.createdAt)}</span>
      </button>

      <div className="wf-run-bar" aria-hidden>
        <span
          className={execution.status.toLowerCase()}
          style={{ width: `${execution.totalNodes ? (execution.doneNodes / execution.totalNodes) * 100 : 0}%` }}
        />
      </div>

      {open && (
        <div className="wf-run-body">
          <div className="wf-run-actions">
            {live ? (
              <button type="button" className="btn ghost" onClick={cancel}>
                <Icon name="stop" size={13} /> Cancel run
              </button>
            ) : (
              <button type="button" className="btn danger-ghost" onClick={remove}>
                <Icon name="delete" size={13} /> Delete run
              </button>
            )}
            {!live && execution.retryableNodes > 0 && (
              <button
                type="button"
                className="btn ghost"
                onClick={retry}
                disabled={retrying}
                title="Run the failed step again, and everything that was waiting on it. The steps that already succeeded keep their output."
              >
                <Icon name="run" size={13} />{' '}
                {retrying ? 'Queueing…' : `Retry ${plural(execution.retryableNodes, 'step')}`}
              </button>
            )}
            {live && !following && (
              <button type="button" className="btn ghost" onClick={() => setFollowing(true)}>
                <Icon name="play" size={13} /> Follow the run
              </button>
            )}
            {execution.costUsd != null && (
              <span className="wf-run-hint">${execution.costUsd.toFixed(4)} total</span>
            )}
          </div>

          {execution.input && (
            <div className="wf-block">
              <span className="section-label">Run input</span>
              <pre className="wf-pre">{execution.input}</pre>
            </div>
          )}
          {execution.error && <p className="wf-node-warn">{execution.error}</p>}

          <WorkflowGraph
            nodes={graphNodes}
            entryNodeId={entryNodeId}
            statuses={statuses}
            selectedId={openNodeId}
            onSelect={pickNode}
            label={`Run #${execution.id} graph — pick a step to see what it did`}
          />

          <ol className="wf-noderuns">
            {execution.nodes.map((run) => (
              <NodeRun
                key={run.id}
                run={run}
                where={`${execution.workflowName} · Run #${execution.id}`}
                isEntry={run.nodeId === entryNodeId}
                open={openNodeId === run.nodeId}
                staleInput={staleInput.has(run.nodeId)}
                onToggle={() => pickNode(run.nodeId)}
                onRerun={(downstream) => rerunFrom(run, downstream)}
                onEdit={(settings) => editStep(run, settings)}
                onAddAfter={(step) => addStepAfter(run, step)}
                onRemove={() => removeStep(run)}
                siblings={graphNodes}
              />
            ))}
          </ol>
        </div>
      )}
    </li>
  )
})

function NodeRun({
  run, where, isEntry, open, staleInput, siblings, onToggle, onRerun, onEdit, onAddAfter, onRemove,
}) {
  const [log, setLog] = useState('')
  const [rerunning, setRerunning] = useState(false)
  // Whether the "add a step after this one" form is showing. Closed by default and
  // per-step, because where a step goes is the decision the form is for.
  const [adding, setAdding] = useState(false)
  const running = run.status === 'RUNNING'

  async function rerun(downstream) {
    setRerunning(true)
    try {
      await onRerun(downstream)
    } finally {
      setRerunning(false)
    }
  }

  // Stream the session transcript while this node is working. Polled only for the
  // node that is actually running — a finished node's durable output is on the row.
  const runIdRef = useRef(run.id)
  runIdRef.current = run.id
  useEffect(() => {
    if (!running) return undefined
    let cancelled = false
    const tick = async () => {
      try {
        const { log: text } = await api.nodeRunLog(runIdRef.current)
        // Same transcript as last tick: leave the <pre> alone. Re-setting it would
        // rewrite the text node and drop any selection made inside it.
        if (!cancelled) setLog((current) => (current === text ? current : text))
      } catch {
        /* transient — keep the last transcript */
      }
    }
    tick()
    const t = setInterval(tick, 1500)
    return () => { cancelled = true; clearInterval(t) }
  }, [running])

  // A retried step is the same box with a second history behind it, so say which
  // attempt this is — otherwise a step that reads "Done" with a failure in its log
  // looks like the page is lying. runMeta's figures are totals across the attempts.
  // "prompt edited" rides along on the collapsed row for the same reason: the row says
  // "Done" over an answer the prompt in the box no longer asked for, and that is worth
  // knowing without opening the step.
  const stale = outputPredatesSettings(run)
  const meta = [
    run.attempt > 1 ? `attempt ${run.attempt}` : '',
    // The level this step's session was actually started with, which the run
    // snapshotted — so a run keeps saying what it did after the workflow moves on.
    // Absent on a step left at the default, where there is nothing to explain.
    run.effort ? `effort ${run.effort}` : '',
    stale ? 'settings edited' : '',
    staleInput ? 'input changed' : '',
    runMeta(run),
  ]
    .filter(Boolean)
    .join(' · ')

  return (
    <li className={`wf-noderun ${run.status.toLowerCase()} ${open ? 'open' : ''}`}>
      <button type="button" className="wf-noderun-head" onClick={onToggle}>
        <span className={`wf-dot ${run.status.toLowerCase()}`} aria-hidden />
        <span className="wf-noderun-name">{run.nodeName}</span>
        {isEntry && (
          <span className="wf-badge entry-badge" title="This run started here">Entry</span>
        )}
        {/* The one thing in the record that reading the workflow would never explain. */}
        {run.addedAt && (
          <span
            className="wf-badge added-badge"
            title="Added to this run while debugging it — the workflow doesn't have this step"
          >
            Added
          </span>
        )}
        <span className="wf-noderun-state">{stateLabel(run.status)}</span>
        {meta && <span className="wf-noderun-meta">{meta}</span>}
      </button>

      {open && (
        <div className="wf-noderun-body">
          {/* Zero on a live run and on a step the entry never reached — the server
              decides both, so the buttons are offered exactly when they would work. */}
          {run.rerunFromCount > 0 && (
            <div className="wf-noderun-actions">
              <button
                type="button"
                className="btn ghost"
                onClick={() => rerun(true)}
                disabled={rerunning}
                title="Run this step again from its own input, and every step that reads its output. The steps before it keep what they produced."
              >
                <Icon name="run" size={13} /> {rerunning ? 'Queueing…' : rerunLabel(run.rerunFromCount)}
              </button>
              {/* Only where it is a different offer from the button beside it: on the
                  last step of a branch the cascade is already one step. `canRerunAlone`
                  is the server's own answer, false where the step has no stored input
                  waiting for it — the one case redoing it alone would just discard the
                  output and skip. */}
              {run.canRerunAlone && run.rerunFromCount > 1 && (
                <button
                  type="button"
                  className="btn ghost"
                  onClick={() => rerun(false)}
                  disabled={rerunning}
                  title="Run only this step, for one session. The steps after it keep what they produced — which was written from this step's previous output."
                >
                  <Icon name="run" size={13} /> {rerunning ? 'Queueing…' : 'Just this step'}
                </button>
              )}
              {/* Adding a step is free — it lands queued and starts nothing — so this
                  one has no confirm on it; the bill is the re-run button on the new
                  row. Removing one destroys an answer, so that one does. */}
              <button
                type="button"
                className="btn ghost"
                onClick={() => setAdding((v) => !v)}
                aria-expanded={adding}
                title="Add a step to this run that reads this one's output. It arrives queued — nothing runs until you start it."
              >
                <Icon name="plus" size={13} /> {adding ? 'Cancel' : 'Add step after'}
              </button>
              {!isEntry && (
                <button
                  type="button"
                  className="btn danger-ghost"
                  onClick={onRemove}
                  title="Take this step out of the run. What it produced is deleted; the steps that read it will read what it read."
                >
                  <Icon name="delete" size={13} /> Remove step
                </button>
              )}
            </div>
          )}
          {adding && (
            <AddStepAfter
              after={run.nodeName}
              onCancel={() => setAdding(false)}
              onAdd={async (step) => { await onAddAfter(step); setAdding(false) }}
            />
          )}
          <NodeRunSettings
            run={run}
            siblings={siblings}
            isEntry={isEntry}
            /* Editable exactly where the re-run button is offered — the server enforces
               the same rule, since changing a step that can never be run would only
               falsify the record of what this run did. */
            editable={run.rerunFromCount > 0}
            stale={stale}
            onSave={onEdit}
          />
          {run.input && (
            <details className="wf-details">
              <summary>Input it was given</summary>
              <pre className="wf-pre">{run.input}</pre>
            </details>
          )}
          {running && (
            <div className="wf-block">
              <span className="section-label live">Live session</span>
              <pre className="logbox wf-log">{log || 'Starting…'}</pre>
            </div>
          )}
          {stale && (run.output || run.error) && (
            <p className="wf-node-warn">
              This step was edited after it ran, so what's below is the previous
              settings' answer. Re-run the step to test the change.
            </p>
          )}
          {/* Same shape of warning, one step removed: there the prompt moved under the
              answer, here the input did. Both say "this row reads Done over something
              that is no longer the current question". */}
          {staleInput && (run.output || run.error) && (
            <p className="wf-node-warn">
              A step above this one was re-run on its own after this ran, so what's below
              was written from an input that has since changed. Re-run this step to bring
              it up to date.
            </p>
          )}
          {run.output && (
            <div className="wf-block">
              <span className="section-label">
                Output
                {/* The output box is 340px tall between a graph and the next step —
                    enough to see that there is an answer, not to read one. */}
                <MaximizeButton
                  title={run.nodeName}
                  subtitle={where}
                  meta={[stateLabel(run.status), meta]}
                  text={run.output}
                  what="step output"
                />
              </span>
              <div className="wf-output"><Markdown text={run.output} /></div>
            </div>
          )}
          {run.error && (
            <div className="wf-block">
              <span className="section-label">
                Why it stopped
                <MaximizeButton
                  title={`${run.nodeName} — failed`}
                  subtitle={where}
                  meta={[stateLabel(run.status), meta]}
                  text={run.error}
                  mono
                  what="failure"
                />
              </span>
              <pre className="wf-pre error-pre">{run.error}</pre>
            </div>
          )}
          {!running && !run.output && !run.error && (
            <p className="wf-empty">Hasn't run yet.</p>
          )}
        </div>
      )}
    </li>
  )
}

/**
 * A step being added to a run that has already finished — the debugging move the
 * editing boxes can't make.
 *
 * Kept to name, prompt and effort. Where it goes is not asked, because the button that
 * opened this form already answered it: the new step reads the step it was opened on.
 * Offering a free choice of wiring here would be a second graph editor inside a run,
 * for the one case ("after this") that is almost always meant — and the wiring is
 * editable on the new row a moment later anyway.
 */
function AddStepAfter({ after, onCancel, onAdd }) {
  const [name, setName] = useState('')
  const [prompt, setPrompt] = useState('')
  const [effort, setEffort] = useState('')
  const [saving, setSaving] = useState(false)
  const [problem, setProblem] = useState(null)
  const id = useId()

  async function add() {
    setSaving(true)
    setProblem(null)
    try {
      await onAdd({ name: name.trim(), prompt, effort: effort || null })
    } catch (e) {
      setProblem(cleanError(e))
      setSaving(false)
    }
  }

  return (
    <div className="wf-add-step">
      <span className="section-label">New step, reading “{after}”</span>
      <input
        className="wf-node-name"
        value={name}
        placeholder="Step name"
        maxLength={200}
        disabled={saving}
        onChange={(e) => setName(e.target.value)}
        aria-label="Name of the step to add"
      />
      <textarea
        className="composer-desc wf-node-prompt"
        rows={4}
        placeholder={`What to do with “${after}”’s answer`}
        value={prompt}
        maxLength={20000}
        disabled={saving}
        onChange={(e) => setPrompt(e.target.value)}
        aria-label="Prompt for the step to add"
      />
      <EffortPicker id={`add-effort-${id}`} value={effort} onChange={setEffort} />
      <div className="wf-noderun-actions">
        <button type="button" className="btn" onClick={add} disabled={!name.trim() || saving}>
          {saving ? 'Adding…' : 'Add step'}
        </button>
        <button type="button" className="btn ghost" onClick={onCancel} disabled={saving}>
          Cancel
        </button>
        <span className="wf-run-hint">
          It arrives queued and nothing starts — the re-run button on the new step is
          what spends a session. The workflow is not changed.
        </span>
      </div>
      {problem && <p className="wf-node-warn">{problem}</p>}
    </div>
  )
}

/** What a step's settings are, for comparing a draft against the server's copy. */
function settingsOf(run) {
  return {
    name: run.nodeName,
    prompt: run.prompt || '',
    // '' rather than null, so the <select> is a controlled component on the same
    // value the "Default" option carries — the same swap draftFrom makes.
    effort: run.effort || '',
    inputs: run.dependsOn || [],
  }
}

/**
 * Two settings objects are the same when they would save the same. The order inputs
 * were ticked in is not part of what a step is, so toggling a connection off and on
 * again must not leave the form claiming there is something to save.
 */
function sameSettings(a, b) {
  return JSON.stringify([a.name, a.prompt, a.effort, [...a.inputs].sort()])
    === JSON.stringify([b.name, b.prompt, b.effort, [...b.inputs].sort()])
}

/**
 * One step exactly as this run holds it — and, on a finished run, all of it editable.
 *
 * The pair "edit the step, re-run the step" is the whole point. Before it, trying a
 * different version of a step meant editing the workflow and running the graph from
 * the top: you paid for every step above the one in question and compared the new
 * answer against a freshly-generated everything-else. Editing the run's own copy
 * changes one variable — the step is handed exactly the input it was handed before —
 * and costs one session.
 *
 * All four settings and not only the prompt, because "was this worth ultracode" and
 * "should it have been reading step two as well" are the questions a disappointing
 * step actually raises, and neither is a change of wording.
 *
 * The edit stays in the run. A version that turns out to be better is copied into the
 * editor by hand, which is the moment it stops being an experiment.
 */
function NodeRunSettings({ run, siblings, isEntry, editable, stale, onSave }) {
  const server = useMemo(() => settingsOf(run), [run])
  const [draft, setDraft] = useState(server)
  const [saving, setSaving] = useState(false)
  const [problem, setProblem] = useState(null)
  // Open on its own when the boxes are the thing to look at: the step has been
  // changed and the output below it is the old settings' work.
  const [open, setOpen] = useState(stale)
  const id = useId()

  // The runs pane polls, so `server` can move underneath the form — a re-run
  // finishing, or the same run edited in another tab. Adopt the new settings only
  // when there is nothing to lose by it; half-typed keystrokes outrank a poll.
  const seen = useRef(server)
  useEffect(() => {
    setDraft((current) => (sameSettings(current, seen.current) ? server : current))
    seen.current = server
  }, [server])

  // Anything downstream of this step would close a loop, so it isn't offered — the
  // same rule the editor's chips follow, and the server refuses it either way.
  const blocked = useMemo(() => descendantsOf(siblings, run.nodeId), [siblings, run.nodeId])
  const candidates = siblings.filter((n) => n.id !== run.nodeId && !blocked.has(n.id))
  const dirty = !sameSettings(draft, server)

  function toggleInput(from) {
    setDraft((d) => ({
      ...d,
      inputs: d.inputs.includes(from) ? d.inputs.filter((i) => i !== from) : [...d.inputs, from],
    }))
  }

  async function save() {
    setSaving(true)
    setProblem(null)
    try {
      await onSave({
        name: draft.name.trim() || 'Untitled step',
        prompt: draft.prompt,
        // Back to null on the way out: the API reads blank as "the default".
        effort: draft.effort || null,
        inputs: draft.inputs,
      })
    } catch (e) {
      // Keep the draft: it is what the user typed, and the usual causes — the run
      // moved, or the wiring would loop — are things they can act on without retyping.
      setProblem(cleanError(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <details
      className="wf-details"
      open={open}
      onToggle={(e) => setOpen(e.currentTarget.open)}
    >
      <summary>
        Step settings{run.settingsEditedAt ? ` · edited ${relTime(run.settingsEditedAt)}` : ''}
      </summary>
      {editable ? (
        <div className="wf-prompt-edit">
          <input
            className="wf-node-name"
            value={draft.name}
            placeholder="Step name"
            maxLength={200}
            disabled={saving}
            onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))}
            aria-label={`Name of ${run.nodeName}`}
          />
          <textarea
            className="composer-desc wf-node-prompt"
            rows={6}
            value={draft.prompt}
            spellCheck={false}
            disabled={saving}
            onChange={(e) => setDraft((d) => ({ ...d, prompt: e.target.value }))}
            aria-label={`Prompt for ${run.nodeName}`}
          />
          <EffortPicker
            id={`run-effort-${id}`}
            value={draft.effort}
            onChange={(effort) => setDraft((d) => ({ ...d, effort }))}
          />
          <div className="wf-inputs">
            <span className="wf-inputs-label">Input from</span>
            {isEntry ? (
              // Not a picker at all here. The entry is handed the run's own input, and
              // the server refuses to wire it to a step — offering chips that can only
              // be rejected would be a worse way to learn that.
              <span className="wf-inputs-none">whatever you typed when starting the run</span>
            ) : candidates.length === 0 ? (
              <span className="wf-inputs-none">nothing else in this run to read</span>
            ) : (
              candidates.map((other) => (
                <button
                  key={other.id}
                  type="button"
                  className={`wf-chip ${draft.inputs.includes(other.id) ? 'on' : ''}`}
                  disabled={saving}
                  onClick={() => toggleInput(other.id)}
                >
                  {other.name || 'Untitled step'}
                </button>
              ))
            )}
          </div>
          <div className="wf-noderun-actions">
            <button type="button" className="btn" onClick={save} disabled={!dirty || saving}>
              {saving ? 'Saving…' : 'Save step'}
            </button>
            {dirty && !saving && (
              <button type="button" className="btn ghost" onClick={() => setDraft(server)}>
                Discard
              </button>
            )}
            <span className="wf-run-hint">
              {dirty
                ? 'Unsaved — saving changes this run only; the workflow keeps its own copy.'
                : run.settingsEditedAt
                  ? 'Saved to this run. Re-run the step above to see the effect.'
                  : 'This run\u2019s own copy. Change it, then re-run the step to test it.'}
            </span>
          </div>
          {problem && <p className="wf-node-warn">{problem}</p>}
        </div>
      ) : (
        <pre className="wf-pre">{server.prompt || 'This step has no prompt of its own.'}</pre>
      )}
    </details>
  )
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

function stateLabel(status) {
  return {
    PENDING: 'Queued',
    RUNNING: 'Running',
    SUCCEEDED: 'Done',
    FAILED: 'Failed',
    SKIPPED: 'Skipped',
    CANCELLED: 'Cancelled',
  }[status] || status
}

/**
 * What re-running one step actually redoes. The count is the whole warning: clicking
 * "Re-run this step" is a small decision, "+ 5 steps after it" is not the same one.
 */
function rerunLabel(count) {
  return count === 1 ? 'Re-run this step' : `Re-run this step + ${plural(count - 1, 'step')} after it`
}

/** "1 step" / "3 steps" — the count is the point, so it stays in front. */
function plural(count, noun) {
  return `${count} ${noun}${count === 1 ? '' : 's'}`
}

function truncate(text, max) {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text
}

/**
 * Server errors arrive as "400 Bad Request <body>". The status is noise next to the
 * sentence the API wrote ("The nodes form a cycle…"), which is the part that tells
 * the user what to change — so show that when we can find it.
 */
function cleanError(e) {
  const message = e?.message || 'Something went wrong'
  const detail = message.match(/"message"\s*:\s*"([^"]+)"/)
  return detail ? detail[1] : message
}
