"""Reorder a PEM certificate bundle into TLS wire order.

    order-chain.py <unordered.pem> <private.key> <out.pem>

Key Vault returns a PKCS#12 chain root-first, but a TLS server must present the
LEAF first, with each following certificate having signed the one before it.
This finds the leaf by matching the private key's public key, walks
subject->issuer to build the chain, and drops a self-signed root (clients must
already trust it locally; sending it only adds bytes to every handshake).
"""
import re
import subprocess
import sys

unordered, keyfile, out = sys.argv[1:4]

blocks = re.findall(
    r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----\n?",
    open(unordered).read(),
    re.S,
)


def openssl(args, data):
    return subprocess.run(["openssl", *args], input=data, capture_output=True).stdout


def field(pem, flag):
    out = openssl(["x509", "-noout", flag], pem.encode()).decode()
    return out.split("=", 1)[1].strip() if "=" in out else out.strip()


def cn(subject):
    m = re.search(r"CN\s*=\s*([^,/]+)", subject)
    return m.group(1).strip() if m else subject


certs = [
    {
        "pem": p,
        "subject": field(p, "-subject"),
        "issuer": field(p, "-issuer"),
        "pub": openssl(["x509", "-pubkey", "-noout"], p.encode()),
    }
    for p in blocks
]

key_pub = openssl(["pkey", "-pubout"], open(keyfile, "rb").read())
leaf = next((c for c in certs if c["pub"] == key_pub), None)
if leaf is None:
    sys.exit("no certificate in the bundle matches the private key")

chain, seen = [leaf], {id(leaf)}
while chain[-1]["subject"] != chain[-1]["issuer"]:
    nxt = next(
        (c for c in certs if c["subject"] == chain[-1]["issuer"] and id(c) not in seen),
        None,
    )
    if nxt is None:
        break
    chain.append(nxt)
    seen.add(id(nxt))

# Trim a self-signed root if the walk collected one.
if len(chain) > 1 and chain[-1]["subject"] == chain[-1]["issuer"]:
    chain.pop()

with open(out, "w") as fh:
    fh.write("".join(c["pem"] for c in chain))

print("  chain (%d): %s" % (len(chain), " -> ".join(cn(c["subject"]) for c in chain)))
