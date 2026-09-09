import { memo, useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { api } from './api.js'
import Icon from './Icon.jsx'
import Markdown from './Markdown.jsx'
import { MaximizeButton } from './resultWindow.jsx'
import { relTime, runMeta } from './time.js'
import {
  NODE_H, NODE_W, descendantsOf, edgePath, inRunOrder, layoutGraph, reachableFrom,
} from './workflowGraph.js'
import { followedNode, mergeExecutions } from './workflowLive.js'
import { paneColumns, readCollapsed, writeCollapsed } from './workflowPanes.js'
// The editor's working copy and its conversions to/from what the API stores —
// nodes carry their own `inputs` rather than a separate edge list, because that is
// how they are edited. See workflowDraft.js.
import { blankDraft, draftFingerprint, draftFrom, newId, payloadFrom } from './workflowDraft.js'

// The Workflows page: author a DAG of prompt tasks on the left, watch the runs it
// produced on the right. Both halves live on one page because they are two views of
// the same thing — you tweak a prompt, run it, read what came out, tweak again.
//
// The one idea the UI has to get across is that nodes DON'T share a conversation.
// Every node is a fresh session; the only thing that travels along an edge is the
// upstream node's output text, pasted into the next node's input. So the editor
// talks about "input from", and an execution shows each node's input next to its
// output rather than one merged transcript.

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function WorkflowsPage({ onBack }) {
  const [workflows, setWorkflows] = useState([])
  const [executions, setExecutions] = useState([])
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
      } catch (e) {
        setError(e.message)
      }
    })()
  }, [loadWorkflows, loadExecutions])

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

function WorkflowEditor({ draft, onChange, onSave, onRun, onDuplicate, onDelete, busy }) {
  const [runInput, setRunInput] = useState('')
  // The graph is the editing surface: click a step in the picture and its card
  // opens underneath. Every prompt shown at once turned the page into a column of
  // textareas in which the shape of the graph — the thing being authored — was the
  // one thing you couldn't see.
  const [selectedId, setSelectedId] = useState(draft.entryNodeId)
  const update = (patch) => onChange({ ...draft, ...patch })

  const setNode = (id, patch) =>
    update({ nodes: draft.nodes.map((n) => (n.id === id ? { ...n, ...patch } : n)) })

  const addNode = () => {
    const id = newId()
    setSelectedId(id)
    // A new step is wired to the last one by default: a chain is what people mean
    // most of the time, and unhooking is easier than hunting for the connection.
    const previous = draft.nodes[draft.nodes.length - 1]
    update({
      nodes: [
        ...draft.nodes,
        { id, name: `Step ${draft.nodes.length + 1}`, prompt: '', inputs: previous ? [previous.id] : [] },
      ],
      entryNodeId: draft.entryNodeId || id,
    })
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

function NodeEditor({
  node, index, all, isEntry, unreached, onChange, onMakeEntry, onToggleInput, onRemove,
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
        {isEntry ? (
          <span className="wf-badge entry-badge" title="Every run starts here">Entry</span>
        ) : (
          <button type="button" className="wf-make-entry" onClick={onMakeEntry}>
            Make entry
          </button>
        )}
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
  async function rerunFrom(run) {
    const also = run.rerunFromCount - 1
    const ok = window.confirm(
      `Re-run "${run.nodeName}"${also ? ` and the ${plural(also, 'step')} after it` : ''}?\n\n`
      + `${also ? 'Those steps read this one\'s output, so what they produced is discarded and each ' : 'It '}`
      + `runs a fresh Claude session — real minutes and real money. The steps before `
      + `"${run.nodeName}" are untouched and keep their output.`,
    )
    if (!ok) return
    try {
      await api.retryNodeRun(run.id)
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
            entryNodeId={execution.nodes[0]?.nodeId}
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
                open={openNodeId === run.nodeId}
                onToggle={() => pickNode(run.nodeId)}
                onRerun={() => rerunFrom(run)}
              />
            ))}
          </ol>
        </div>
      )}
    </li>
  )
})

function NodeRun({ run, where, open, onToggle, onRerun }) {
  const [log, setLog] = useState('')
  const [rerunning, setRerunning] = useState(false)
  const running = run.status === 'RUNNING'

  async function rerun() {
    setRerunning(true)
    try {
      await onRerun()
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
  const meta = [run.attempt > 1 ? `attempt ${run.attempt}` : '', runMeta(run)]
    .filter(Boolean)
    .join(' · ')

  return (
    <li className={`wf-noderun ${run.status.toLowerCase()} ${open ? 'open' : ''}`}>
      <button type="button" className="wf-noderun-head" onClick={onToggle}>
        <span className={`wf-dot ${run.status.toLowerCase()}`} aria-hidden />
        <span className="wf-noderun-name">{run.nodeName}</span>
        <span className="wf-noderun-state">{stateLabel(run.status)}</span>
        {meta && <span className="wf-noderun-meta">{meta}</span>}
      </button>

      {open && (
        <div className="wf-noderun-body">
          {/* Zero on a live run and on a step the entry never reached — the server
              decides both, so the button is offered exactly when it would work. */}
          {run.rerunFromCount > 0 && (
            <div className="wf-noderun-actions">
              <button
                type="button"
                className="btn ghost"
                onClick={rerun}
                disabled={rerunning}
                title="Run this step again from its own input, and every step that reads its output. The steps before it keep what they produced."
              >
                <Icon name="run" size={13} /> {rerunning ? 'Queueing…' : rerunLabel(run.rerunFromCount)}
              </button>
            </div>
          )}
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
