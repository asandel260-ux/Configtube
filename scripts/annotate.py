import sys, glob
import xml.etree.ElementTree as ET

log, title = sys.argv[1], sys.argv[2]
lines = open(log, errors="replace").read().splitlines()
out = []
for i, l in enumerate(lines):
    if l.startswith("e: ") or "What went wrong" in l or l.startswith("Execution failed"):
        out.extend(lines[i:i + 4])
for f in glob.glob("app/build/test-results/**/*.xml", recursive=True):
    try:
        root = ET.parse(f).getroot()
    except Exception:
        continue
    for tc in root.iter("testcase"):
        for kind in ("failure", "error"):
            for fe in tc.findall(kind):
                out.append("TEST FAILED %s.%s: %s" % (tc.get("classname"), tc.get("name"), (fe.get("message") or "")[:300]))
if not out:
    out = lines[-40:]
msg = "\n".join(out[:60]).replace("%", "%25").replace("\r", "").replace("\n", "%0A")
print("::error title=" + title + "::" + msg)
