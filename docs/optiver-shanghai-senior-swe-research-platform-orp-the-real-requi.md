# Optiver Shanghai — Senior SWE, Research Platform (ORP): The Real Requirements

**Role:** Senior Software Engineer – Research Platform (ORP) · Shanghai · Experienced IC
**Posting:** https://www.optiver.com/join-us/jobs/technology/shanghai/senior-software-engineer-research-platform/
**Prepared for:** Xianlong Lu · 2026-09-07

> Method: live posting pulled and decoded via a multi-agent analysis — five decode lenses
> (requirements, business model, seniority, plus web research on interview & comp), two
> adversarial fact-checkers on the researched claims, and a synthesis. Confidence caveats
> are carried through honestly.

---

## 1. What this role is *really* asking for

**In one sentence:** they want a senior distributed-systems engineer who can *own an internal
research-compute platform as a product* — from scheduler/cluster internals up to the Python/Rust
APIs researchers call — and make it measurably faster and more reliable under real load, while
setting direction autonomously and having the spine to tell a researcher "no."

The load-bearing fact is the **business model**. At a market maker, researcher iteration speed
*is* the firm's rate of edge discovery, and edge is directly monetized (that "global profit pool"
bonus). So the platform's north-star metric is **human iteration latency — idea → measured result
— not cost or uptime.** And because a subtly wrong backtest loses real money,
**correctness/reproducibility is a first-order feature**, not a nice-to-have. That is what
separates this from a generic FAANG platform job.

**The real filter:** *"Can you build and run a high-throughput, low-turnaround research compute
platform, without hand-holding, where every hour of experiment latency has P&L consequences?"*
Raw distributed-systems depth + platform-ownership judgment beat trading-domain knowledge or
LeetCode skill.

---

## 2. Decoding the bullets

| Posting phrase | What it really means | Bar |
|---|---|---|
| "service orchestration, job scheduling, cluster management, big-data processing" | You work on the internal **Slurm/Ray/K8s/Airflow equivalents themselves** — the scheduler and cluster manager, not glue. High blast radius; researchers see regressions instantly. | **Hard gate** |
| "fully managed compute & data platform layer… in Python & Rust" | Platform-as-product. Python = the API/orchestration surface; **Rust = the hot paths** (schedulers, data-plane, daemons). They've hit Python's ceiling and are rewriting core in Rust. | **Hard gate** (Python); Rust *near-expected*, not really "a plus" |
| "leading large-scale engineering projects" | **The seniority gate.** Technical ownership of a multi-quarter, multi-person effort you *drove* — not people-management. Screens out strong-IC-who-only-executes-tickets. | **Hard gate** |
| "deep understanding of CS fundamentals" + "strong instincts" | The HFT bar: memory hierarchy, concurrency, OS scheduling, complexity, hardware-perf effects. On a compute platform, **performance is the product.** | **Hard gate** |
| "experience in distributed systems" | Flat phrasing hides that this is likely the **highest-weight round**. Consistency, partial failure, idempotency, backpressure, scheduling under failure. ORP *is* a distributed system. | **Hard gate** |
| "Expertise in Python required; C/C++/Rust a plus" | "Expertise" (not proficiency) = senior Python: asyncio, GIL, profiling, memory, the C-extension/FFI boundary. Rust "plus" is understated for *this* team. | **Hard gate** (Python) |
| "GPUs and CUDA a plus" | Genuine differentiator. Note: "**working with** GPUs" = *operating/scheduling* them (topology, MIG, NCCL, utilization), **not** writing kernels. Infra-side GPU counts. Level/comp lever. | Differentiator |
| "intersection of infrastructure and research enablement" | Strong infra eng who *also* enjoys serving researchers as customers. Success = researcher productivity, not infra elegance. A warning to pure-infra purists. | Differentiator |
| "readiness to set technical direction… do the right thing" | The most loaded line. **Autonomous judgment** in global async gaps + **the spine to resist pressure** (from researchers or your own convenience) to cut corners on correctness. High-trust, low-hand-holding, integrity culture. | **Hard gate** |

### Hidden requirements not written down
- **Reproducibility / determinism as first-class** — point-in-time correctness, no lookahead/survivorship bias, deterministic distributed execution. A wrong backtest loses money.
- **End-to-end profiling of the whole loop** — the p99 tail (scheduling overhead, data-load, cold-start, result retrieval), not just your own service.
- **On-call / operational ownership** — "fully managed" + "reliability" + "directly interface with" = incidents, SLOs, being paged when researchers are blocked.
- **Functional-to-fluent English** — globally distributed team; the loop and daily work run in English despite the Shanghai location.
- **Willingness to work inconvenient hours** for EU/US/AU sync, plus strong async written comms.
- **Hardware-reasoning awareness** — RDMA/InfiniBand, NUMA, storage tiering, GPU topology ("datacenter hardware to tooling").
- **Longevity/stability signal** — quant firms invest heavily per hire and dislike job-hoppers for senior platform roles; a coherent increasing-ownership arc matters.
- **Level/comp calibration happens in the loop** — GPU/CUDA, Rust depth, and demonstrated direction-setting are the levers that move level and the (large) bonus.

---

## 3. What "Senior" means here

Optiver runs a **flat, IC-heavy ladder.** "Senior" is a strong hands-on IC with embedded tech
leadership — **not** a manager or pure architect. You're expected to be in the codebase shipping
Python/Rust *and* to lead-without-authority (own a workstream, set the approach, unblock others).
Capability-wise it maps roughly to big-tech senior→early-staff, but the title just signals
"trusted autonomous IC."

- **Senior vs mid** = autonomy + direction-setting (owns the design and outcome vs executes a given design).
- **Senior vs staff** = scope of influence (one workstream vs platform-wide/cross-office strategy).
- This req is scoped to project-level, so you do **not** need a platform-wide-strategy track record — showing *readiness* to grow into it is the plus.

---

## 4. Interview & comp reality (verified, with caveats)

### Interview
- Optiver officially prioritizes **how you think over LeetCode**: collaborative problem-solving, heavy CS fundamentals, "explain what your language does under the hood" *(well-supported, primary source)*.
- For *this ORP role specifically*: **Python-first distributed-systems + system design dominate — the C++ low-latency gauntlet is the trading-systems track, not this one** *(well-supported, primary JD)*.
- Likely loop: **OA (coding-focused, possibly timed) → recruiter screen → 1–2 technical rounds (live Python + CS fundamentals + distributed-systems design) → project deep-dive + behavioral/values** *(shape well-supported)*.
- **Down-weight these:** the "6-stage Shanghai loop ending in a CTO interview" rests on **one bot-walled Glassdoor snippet — weak, do not rely on it.** Whether senior platform hires face the trader-style mental-math OA is **unconfirmed** (that evidence is mostly grad/trader track). Prep for a *possible* timed element; don't assume the mental-math gauntlet.

### Comp (RMB)
- The **global profit pool** bonus is confirmed verbatim on the posting *(well-supported)*.
- **Realistic total ~1.2M–2.0M in the first ~2 years.** Base verified from Liepin listings at ~50–80k/month × 24薪 (~1.2M–1.9M) *(well-supported)*.
- The oft-cited "**~2M/yr years 1–2, then convert to the global profit pool from year 3**" figure is **one anonymous 1point3acres self-report — plausible, not fact.**
- **~2–4× the general Shanghai senior-SWE market** (~0.5M avg); upper-middle quant tier, below Jane Street / Citadel Securities HK *(plausible)*.
- **Essentially no meaningful equity in China** — cash base + cash profit-pool bonus; equity/RSU is a US-office feature *(plausible, argument-from-absence)*.
- Sentiment caveat *(low confidence, opinion)*: Chinese forums flag a perceived promotion ceiling / narrow lateral mobility for foreign quant firms' China staff.

---

## 5. Candidate fit — Xianlong Lu

### True strengths (well-aligned)
- **Genuine large-scale distributed-systems ownership** — tech lead of a self-built cloud-native graph DB on K8s powering metadata services; owned Data Lineage in MS Purview end-to-end (extended Apache Atlas + OpenLineage) as a managed platform service. Real platform-as-a-product ownership → hits the seniority gate *and* the "leading large-scale projects" gate.
- **Proven high-throughput scaling under real load** — HBase/Hadoop from hundreds → tens-of-thousands TPS over hundreds of TB, rowkey design to kill hotspots, cache/storage tuning, live incident handling, cluster mgmt/node expansion. Exactly the end-to-end performance + operational-ownership + straggler/hotspot profile; strong war stories for the failure-mode grilling.
- **Platform-serving-internal-customers mindset** — multi-tenant schemas, zero-downtime re-sync/index rebuild via MQ pipelines. Maps to "fully managed platform," idempotency, degradation modes.
- **Strong CS fundamentals / raw aptitude** — two-time China Physics Olympiad national 2nd prize, strong math. Covers the fundamentals bar and any timed/quantitative screen.
- **Current sharp Python** — recent LLM-agent tooling with a reproducible evaluation harness (on-point given the reproducibility/determinism emphasis).
- **Coherent 11-year increasing-ownership arc** (CMB → MS 7y as tech lead) — satisfies the longevity signal. First cross-team Istio adopter shows the "evaluate & adopt new tech" differentiator.

### True gaps
- **No production Rust** — most consequential; for *this* team Rust is closer to expected than "a plus."
- **No CUDA/GPU** — differentiator, not a gate, but a level/comp lever left on the table and a likely soft spot in GPU-scheduling design probes.
- **Python depth ≠ infra depth** — deepest systems depth is JVM/infra and C++; the role wants Python *expertise at platform-internals depth* (asyncio, GIL, C-extension/FFI boundary, profiling).
- **No direct scheduler / gang-scheduling / GPU-resource-manager experience** — operated clusters (K8s, Hadoop/HBase) but hasn't built the control-plane that is the literal subject of the highest-weight design round.
- **No trading-domain reproducibility exposure** (point-in-time correctness, lookahead/survivorship bias) — learnable, framable via eval-harness work, but not native.

### The single biggest risk in the loop
**The distributed-systems system-design round on scheduling/orchestration, colliding with the
Rust + Python-internals depth expectation.** You'll design a job scheduler / GPU cluster resource
manager — a system you've *operated adjacent to but never built* — under relentless failure-mode
pressure, in a Python/Rust stack where you have zero production Rust. The danger: a strong-but-
adjacent background reads as "impressive systems engineer who hasn't built *this* system in *this*
stack," landing you below the senior bar or at a lower level/comp band.
*(The C++ low-latency gauntlet is NOT the risk — that's the trading-systems track. Don't prep there.)*

### The 3–4 highest-leverage prep actions
1. **Build a small but real Rust-on-FFI project** (PyO3/maturin): a Python-callable Rust component — e.g., a mini job scheduler or a fast data-movement/serialization daemon. Converts "no production Rust" into "here's Rust I wrote and why I reached for it over Python," and lets you answer ownership/borrowing follow-ups from experience. **Highest ROI by far.**
2. **Deeply prep the scheduler / cluster-resource-manager system design** — gang scheduling, preemption, fair-share vs priority, bin-packing, **GPU topology-aware placement (NVLink/PCIe/MIG, NCCL, fragmentation, utilization-as-throughput)**, backpressure, node-failure-mid-job, exactly-once vs at-least-once, straggler mitigation. Explicitly bridge from your HBase-hotspot/rowkey and K8s work so experience becomes transferable evidence, not a gap.
3. **Reframe every project story at senior altitude in the firm's currency** — lead with the ambiguity *you* resolved and the calls *you personally* made ("I decided X because Y, tradeoff Z"); tie outcomes to **iteration latency / turnaround / reliability under load**, not lines of code or team size. Pre-build a "leading-without-authority" narrative and a "pushed back / chose the harder correct path" story for the values gate. Weave in reproducibility/determinism via the eval-harness work.
4. **Shore up senior-depth Python internals + connect to the business model** — GIL/asyncio/concurrency limits, memory, profiling, C-extension boundary (the exact "explain what your language does under the hood" bar). Prepare a specific **"why ORP"** that names *turnaround-time-as-directly-monetized-product* (generic answers are a documented red flag). Treat GPU-as-infrastructure fluency as a stretch lever for level/comp, not a gate.

### Bottom line
You clear the two hardest, highest-weight gates on paper — genuine large-scale distributed-systems
ownership and proven throughput/reliability scaling under real load — and your honesty flags are
exactly the right ones, which itself signals the intellectual honesty the culture screens for.
You're a credible, in-scope senior candidate. Whether you land the offer (and at what level/comp
band) will be decided almost entirely by the **scheduling/orchestration system-design round** and
how convincingly you close the **Rust and Python-internals depth gap** before the loop. The Rust
FFI project + scheduler design prep are the two actions that move the outcome most.
