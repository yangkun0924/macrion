#!/usr/bin/env python3
"""Tiny helper over a uiautomator XML dump.

usage:
  ui.py find <dump.xml> <regex>   prints "x y" of the first node whose text or content-desc matches (case insensitive)
  ui.py has  <dump.xml> <regex>   exit 0 if a node matches, 1 otherwise
  ui.py texts <dump.xml>          prints every non empty text / content-desc, for the logs
"""
import re
import sys
import xml.etree.ElementTree as ET


def nodes(path):
    try:
        root = ET.parse(path).getroot()
    except Exception:
        return []
    return list(root.iter("node"))


def label(node):
    return [node.get("text") or "", node.get("content-desc") or ""]


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "[0,0][0,0]")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(path, pattern):
    rx = re.compile(pattern, re.I)
    for n in nodes(path):
        if any(rx.search(t) for t in label(n) if t):
            return n
    return None


cmd, path = sys.argv[1], sys.argv[2]
if cmd == "find":
    n = find(path, sys.argv[3])
    if n is None:
        sys.exit(1)
    print(*center(n))
elif cmd == "has":
    sys.exit(0 if find(path, sys.argv[3]) is not None else 1)
elif cmd == "texts":
    for n in nodes(path):
        t = [x for x in label(n) if x]
        if t:
            print(" | ".join(t), n.get("resource-id") or "", n.get("bounds"))
