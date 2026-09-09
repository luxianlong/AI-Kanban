import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from './api.js'
import Icon from './Icon.jsx'
import Markdown from './Markdown.jsx'
import { relTime, runMeta } from './time.js'

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
// Draft shape
// ---------------------------------------------------------------------------
// The editor holds a working copy: nodes carry their own `inputs` (the ids feeding
// them) instead of a separate edge list, because that is how they are edited. It is
// flattened back into { nodes, edges } only when saving.

const newId = () =>
  (crypto.randomUUID ? crypto.randomUUID() : `n${Date.now()}${Math.random()}`).slice(0, 36)

function blankDraft() {
  const entry = newId()
  return {
    id: null,
    name: '',
    description: '',
    entryNodeId: entry,
    nodes: [{ id: entry, name: 'Step 1', prompt: '', inputs: [] }],
  }
}

function draftFrom(workflow) {
  const nodes = workflow.nodes.map((n) => ({
    id: n.id,
    name: n.name,
    prompt: n.prompt || '',
    inputs: workflow.edges.filter((e) => e.to === n.id).map((e) => e.from),
  }))
  return {
    id: workflow.id,
    name: workflow.name,
    description: workflow.description || '',
    entryNodeId: workflow.entryNodeId,
    // Listed in run order, so a card's number means "this step runs Nth" and the
    // entry is always first. The saved order is only insertion order, which after a
    // reload can put a step above the one that feeds it — and then the numbering
    // says the opposite of what the graph does.
    nodes: inRunOrder(nodes),
  }
}

/** Nodes sorted so each comes after everything feeding it; ties keep their order. */
function inRunOrder(nodes) {
  const depth = depths(nodes)
  return nodes
    .map((node, index) => ({ node, index }))
    .sort((a, b) =>
      (depth.get(a.node.id) ?? 0) - (depth.get(b.node.id) ?? 0) || a.index - b.index)
    .map(({ node }) => node)
}

function payloadFrom(draft) {
  return {
    name: draft.name.trim(),
    description: draft.description.trim() || null,
    entryNodeId: draft.entryNodeId,
    nodes: draft.nodes.map((n) => ({ id: n.id, name: n.name.trim() || 'Untitled step', prompt: n.prompt })),
    edges: draft.nodes.flatMap((n) => n.inputs.map((from) => ({ from, to: n.id }))),
  }
}

/**
 * Everything downstream of `startId`, following the inputs the other way. Used to
 * hide the connections that would make a cycle: a node can never be fed by one of
 * its own descendants, and offering the choice only to reject it server-side would
 * be a worse way to learn that.
 */
function descendantsOf(nodes, startId) {
  const out = new Set()
  const queue = [startId]
  while (queue.length) {
    const current = queue.pop()
    for (const node of nodes) {
      if (node.inputs.includes(current) && !out.has(node.id)) {
        out.add(node.id)
        queue.push(node.id)
      }
    }
  }
  return out
}

/**
 * Column (depth) of every node: one past the deepest thing feeding it. Drives the
 * graph preview's left-to-right layout, so the picture matches the order the runner
 * will actually work through the graph.
 */
function depths(nodes) {
  const byId = new Map(nodes.map((n) => [n.id, n]))
  const memo = new Map()
  const visit = (id, seen) => {
    if (memo.has(id)) return memo.get(id)
    if (seen.has(id)) return 0 // a cycle can only exist mid-edit; don't hang on it
    seen.add(id)
    const node = byId.get(id)
    const depth = !node || node.inputs.length === 0
      ? 0
      : 1 + Math.max(...node.inputs.map((from) => visit(from, seen)))
    seen.delete(id)
    memo.set(id, depth)
    return depth
  }
  const result = new Map()
  for (const node of nodes) result.set(node.id, visit(node.id, new Set()))
  return result
}

/** Node ids a run can actually get to by following edges out of the entry node. */
function reachableFrom(nodes, entryNodeId) {
  const seen = new Set(entryNodeId ? [entryNodeId] : [])
  let grew = true
  while (grew) {
    grew = false
    for (const node of nodes) {
      if (!seen.has(node.id) && node.inputs.some((from) => seen.has(from))) {
        seen.add(node.id)
        grew = true
      }
    }
  }
  return seen
}

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

  const loadWorkflows = useCallback(async () => {
    const list = await api.listWorkflows()
    setWorkflows(list)
    return list
  }, [])

  const loadExecutions = useCallback(async () => {
    try {
      setExecutions(await api.listExecutions())
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

      <div className="wf-body">
        <aside className="wf-rail">
          <div className="wf-rail-head">
            <span className="section-label">Workflows</span>
            <button type="button" className="btn ghost wf-new" onClick={startNew}>
              <Icon name="plus" size={14} /> New
            </button>
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
        </aside>

        <main className="wf-editor-pane">
          {draft ? (
            <WorkflowEditor
              draft={draft}
              onChange={setDraft}
              onSave={save}
              onRun={run}
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
        </main>

        <aside className="wf-runs-pane">
          <div className="wf-rail-head">
            <span className="section-label">Executions</span>
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
        </aside>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------

function WorkflowEditor({ draft, onChange, onSave, onRun, onDelete, busy }) {
  const [runInput, setRunInput] = useState('')
  const update = (patch) => onChange({ ...draft, ...patch })

  const setNode = (id, patch) =>
    update({ nodes: draft.nodes.map((n) => (n.id === id ? { ...n, ...patch } : n)) })

  const addNode = () => {
    const id = newId()
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
    update({
      nodes,
      // Deleting the entry has to hand the role to something, or the workflow
      // becomes unsaveable without the user working out why.
      entryNodeId: draft.entryNodeId === id ? nodes.find((n) => !n.inputs.length)?.id ?? nodes[0]?.id : draft.entryNodeId,
    })
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

      <GraphPreview nodes={draft.nodes} entryNodeId={draft.entryNodeId} reachable={reachable} />

      <div className="wf-nodes">
        {draft.nodes.map((node, index) => (
          <NodeEditor
            key={node.id}
            node={node}
            index={index}
            all={draft.nodes}
            isEntry={node.id === draft.entryNodeId}
            unreached={!reachable.has(node.id)}
            onChange={(patch) => setNode(node.id, patch)}
            onMakeEntry={() => update({ entryNodeId: node.id })}
            onToggleInput={(fromId) => toggleInput(node.id, fromId)}
            onRemove={draft.nodes.length > 1 ? () => removeNode(node.id) : null}
          />
        ))}
      </div>

      <button type="button" className="btn ghost wf-add-node" onClick={addNode}>
        <Icon name="plus" size={14} /> Add step
      </button>

      <div className="wf-editor-actions">
        <button type="button" className="btn primary" onClick={onSave} disabled={!canSave}>
          {draft.id ? 'Save changes' : 'Create workflow'}
        </button>
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

/**
 * A left-to-right picture of the graph: one column per depth, curves for the edges.
 * Small on purpose — it exists to answer "did I wire this the way I think?", which
 * a list of "input from" chips alone can't.
 */
function GraphPreview({ nodes, entryNodeId, reachable }) {
  const layout = useMemo(() => {
    const depth = depths(nodes)
    const columns = new Map()
    for (const node of nodes) {
      const d = depth.get(node.id) ?? 0
      if (!columns.has(d)) columns.set(d, [])
      columns.get(d).push(node)
    }
    const COL = 150
    const ROW = 46
    const positions = new Map()
    for (const [d, column] of columns) {
      column.forEach((node, row) => {
        positions.set(node.id, { x: 20 + d * COL, y: 16 + row * ROW })
      })
    }
    const tallest = Math.max(...[...columns.values()].map((c) => c.length), 1)
    return {
      positions,
      width: 20 + (Math.max(...columns.keys(), 0) + 1) * COL,
      height: 16 + tallest * ROW + 12,
    }
  }, [nodes])

  const W = 116
  const H = 30

  return (
    <div className="wf-graph">
      <svg
        viewBox={`0 0 ${layout.width} ${layout.height}`}
        width="100%"
        height={layout.height}
        role="img"
        aria-label="Workflow graph"
      >
        {nodes.flatMap((node) =>
          node.inputs.map((fromId) => {
            const from = layout.positions.get(fromId)
            const to = layout.positions.get(node.id)
            if (!from || !to) return null
            const x1 = from.x + W
            const y1 = from.y + H / 2
            const x2 = to.x
            const y2 = to.y + H / 2
            const mid = (x1 + x2) / 2
            return (
              <path
                key={`${fromId}-${node.id}`}
                className="wf-edge"
                d={`M${x1} ${y1} C${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`}
              />
            )
          }),
        )}
        {nodes.map((node) => {
          const at = layout.positions.get(node.id)
          if (!at) return null
          const isEntry = node.id === entryNodeId
          return (
            <g key={node.id} className={`wf-gnode ${isEntry ? 'entry' : ''} ${reachable.has(node.id) ? '' : 'unreached'}`}>
              <rect x={at.x} y={at.y} width={W} height={H} rx="8" />
              <text x={at.x + W / 2} y={at.y + H / 2 + 4} textAnchor="middle">
                {truncate(node.name || 'Untitled', 16)}
              </text>
            </g>
          )
        })}
      </svg>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Executions
// ---------------------------------------------------------------------------

function ExecutionCard({ execution, showWorkflowName, onChanged }) {
  const [open, setOpen] = useState(false)
  const live = execution.status === 'PENDING' || execution.status === 'RUNNING'

  async function cancel(e) {
    e.stopPropagation()
    try {
      await api.cancelExecution(execution.id)
      onChanged()
    } catch {
      /* the poll will show whatever really happened */
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

          <ol className="wf-noderuns">
            {execution.nodes.map((run) => (
              <NodeRun key={run.id} run={run} />
            ))}
          </ol>
        </div>
      )}
    </li>
  )
}

function NodeRun({ run }) {
  const [open, setOpen] = useState(false)
  const [log, setLog] = useState('')
  const running = run.status === 'RUNNING'

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
        if (!cancelled) setLog(text)
      } catch {
        /* transient — keep the last transcript */
      }
    }
    tick()
    const t = setInterval(tick, 1500)
    return () => { cancelled = true; clearInterval(t) }
  }, [running])

  // A running node opens itself, so the live log is what you see without hunting.
  useEffect(() => { if (running) setOpen(true) }, [running])

  const meta = runMeta(run)

  return (
    <li className={`wf-noderun ${run.status.toLowerCase()}`}>
      <button type="button" className="wf-noderun-head" onClick={() => setOpen((v) => !v)}>
        <span className={`wf-dot ${run.status.toLowerCase()}`} aria-hidden />
        <span className="wf-noderun-name">{run.nodeName}</span>
        <span className="wf-noderun-state">{stateLabel(run.status)}</span>
        {meta && <span className="wf-noderun-meta">{meta}</span>}
      </button>

      {open && (
        <div className="wf-noderun-body">
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
              <span className="section-label">Output</span>
              <div className="wf-output"><Markdown text={run.output} /></div>
            </div>
          )}
          {run.error && (
            <div className="wf-block">
              <span className="section-label">Why it stopped</span>
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
