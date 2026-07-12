# AI supply chain — topology

A visual map of the global **AI supply chain**: everything that has to line up so a
prompt you type in a browser can be answered by a large model. Rendered with
[Mermaid](https://mermaid.js.org/) — GitHub renders these diagrams inline; in an
editor use a Mermaid preview extension.

The chain is best read **bottom-up**: raw materials and fab equipment sit at the
base, silicon and systems in the middle, and models and applications at the top.
Value (and margin) generally rises as you move up; **fragility** (single points of
failure) concentrates at the very bottom. The named companies are representative
leaders, not an exhaustive list.

- **Upstream** — materials, EDA software, IP, and the machines that make chips.
- **Silicon** — chip designers (fabless), foundries, memory, and packaging.
- **Systems** — accelerators assembled into servers, networked into clusters.
- **Infrastructure** — data centers, power, cooling, and the clouds that rent it.
- **Intelligence** — foundation models, the software stack that serves them, and
  the applications people actually use.

---

## 1. Full-stack topology (upstream → application)

The end-to-end flow. Each layer depends on the one below it; the dashed arrows on
the right call out the scarce inputs that gate the whole stack.

```mermaid
flowchart TB
    subgraph L0["① Upstream — materials & equipment"]
        direction LR
        mat["Raw materials<br/>silicon wafers · rare earths<br/>neon / palladium · substrates"]
        eda["EDA software<br/>Synopsys · Cadence · Siemens"]
        ip["IP cores<br/>Arm · RISC-V · Imagination"]
        equip["Fab equipment<br/>ASML (EUV) · Applied Materials<br/>Lam · Tokyo Electron · KLA"]
    end

    subgraph L1["② Silicon — design, fab, memory, packaging"]
        direction LR
        design["Chip design (fabless)<br/>Nvidia · AMD · Broadcom<br/>Google TPU · AWS Trainium · Apple"]
        foundry["Foundries<br/>TSMC · Samsung · Intel Foundry"]
        memory["Memory (HBM)<br/>SK hynix · Samsung · Micron"]
        pkg["Advanced packaging<br/>TSMC CoWoS · OSAT (ASE, Amkor)"]
    end

    subgraph L2["③ Systems — accelerators & networking"]
        direction LR
        gpu["AI accelerators<br/>GPUs · TPUs · custom ASICs"]
        net["Interconnect<br/>NVLink · InfiniBand · Ethernet<br/>optics (transceivers)"]
        server["Server / rack OEMs<br/>Supermicro · Dell · HPE · Foxconn"]
    end

    subgraph L3["④ Infrastructure — data centers & clouds"]
        direction LR
        power["Power & cooling<br/>grid · gensets · liquid cooling"]
        dc["Data centers<br/>colo · hyperscale campuses"]
        cloud["Cloud / compute<br/>AWS · Azure · GCP · Oracle<br/>neoclouds: CoreWeave · Lambda"]
    end

    subgraph L4["⑤ Intelligence — models, serving, apps"]
        direction LR
        frameworks["Software stack<br/>CUDA · PyTorch · vLLM · Triton"]
        models["Foundation models<br/>OpenAI · Anthropic · Google<br/>Meta · Mistral · xAI"]
        apps["Applications<br/>assistants · agents · copilots<br/>enterprise & consumer products"]
    end

    L0 --> L1 --> L2 --> L3 --> L4

    equip -. "EUV is the<br/>#1 chokepoint" .-> foundry
    memory -. "HBM supply<br/>gates GPU output" .-> gpu
    pkg -. "CoWoS capacity<br/>gates shipments" .-> gpu
    power -. "grid power is the<br/>emerging bottleneck" .-> dc
```

---

## 2. Chokepoints — where the chain is fragile

Most of the stack has multiple suppliers; a handful of nodes are effectively
sole-source or near-monopoly. These are the places a shock (export control, fire,
earthquake, capacity crunch) propagates through the whole industry.

```mermaid
flowchart LR
    euv["ASML<br/>EUV lithography"]:::choke
    tsmc["TSMC<br/>leading-edge fab (≤3nm)"]:::choke
    cowos["TSMC CoWoS<br/>advanced packaging"]:::choke
    hbm["SK hynix / Samsung / Micron<br/>HBM stacks"]:::choke
    cuda["Nvidia CUDA<br/>software moat"]:::choke
    power["Grid power<br/>& transformers"]:::choke

    euv --> tsmc --> cowos --> gpu["AI accelerators"]
    hbm --> cowos
    cuda -. "lock-in on<br/>the model side" .-> gpu
    gpu --> cluster["GPU clusters"]
    power --> cluster
    cluster --> models["Foundation models"]

    note["Concentration risk:<br/>• 1 credible EUV vendor (ASML)<br/>• ~1 leading-edge foundry (TSMC, Taiwan)<br/>• 3 HBM makers, TSMC-gated packaging<br/>• Nvidia+CUDA dominate training<br/>• Power/interconnect now bind at scale"]
    note -.-> cluster

    classDef choke fill:#fde2e2,stroke:#c0392b,stroke-width:2px;
```

---

## 3. Who plays where — the value chain map

The same players, grouped by the role they occupy. Some giants (Nvidia, Google,
Amazon, Microsoft) are **vertically integrated** and appear implicitly at several
layers — that integration is itself a strategic advantage.

```mermaid
flowchart TB
    subgraph tools["Design & tooling"]
        t1["EDA: Synopsys · Cadence"]
        t2["IP: Arm · RISC-V"]
    end

    subgraph fabgear["Fab equipment"]
        f1["ASML · Applied Materials"]
        f2["Lam Research · Tokyo Electron · KLA"]
    end

    subgraph makers["Silicon makers"]
        m1["Fabless: Nvidia · AMD · Broadcom"]
        m2["Foundry: TSMC · Samsung · Intel"]
        m3["Memory: SK hynix · Micron · Samsung"]
    end

    subgraph iron["Systems & cloud"]
        i1["OEMs: Supermicro · Dell · Foxconn"]
        i2["Clouds: AWS · Azure · GCP · Oracle"]
        i3["Neoclouds: CoreWeave · Lambda · Nebius"]
    end

    subgraph brains["Models & apps"]
        b1["Labs: OpenAI · Anthropic · Google · Meta"]
        b2["Apps: copilots · agents · vertical SaaS"]
    end

    tools --> makers
    fabgear --> makers
    makers --> iron
    iron --> brains
```

---

## How to read this

- **Money flows up, risk pools down.** Application makers capture the most visible
  value, but a disruption at ASML or TSMC would stall every layer above them.
- **The bottleneck moves.** For years the binding constraint was leading-edge fab
  capacity; through 2024–2025 it shifted to **HBM memory** and **CoWoS packaging**,
  and increasingly to **data-center power** and grid interconnection.
- **Geography is destiny.** Leading-edge fabrication is concentrated in Taiwan and
  South Korea; EUV in the Netherlands. This concentration is why AI has become a
  geopolitical, not just a technical, topic (export controls, "chip acts", reshoring).
- **Software locks in hardware.** Nvidia's CUDA ecosystem is a supply-chain node in
  its own right: it makes the accelerator layer sticky even where alternatives exist.

---

_Created as a reference topology of the AI supply chain to aid understanding
(task #84). It is a conceptual map of the industry, not a description of this
dashboard's own architecture — for that, see [`architecture.md`](architecture.md).
Named companies are representative leaders as of 2025–2026 and may change._
