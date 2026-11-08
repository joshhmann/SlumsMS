"""Line-aware reader for the WZ XML dumps (Quest.wz/*.img.xml and friends).

ElementTree throws line numbers away; the atlas cites "file:line" for every fact, so this keeps them. A node is
{"line": n, "props": {name: int|float|str}, "dirs": {name: node}}; repeated names inside one parent keep the last.
"""
import xml.parsers.expat


def parse(path_or_bytes):
    """The top-level <imgdir> children of the file's root imgdir, as {name: node}."""
    data = path_or_bytes
    if not isinstance(data, bytes):
        with open(data, "rb") as f:
            data = f.read()
    root = {"line": 0, "props": {}, "dirs": {}}
    stack = [root]
    p = xml.parsers.expat.ParserCreate()

    def start(tag, attrs):
        if tag == "imgdir":
            node = {"line": p.CurrentLineNumber, "props": {}, "dirs": {}}
            stack[-1]["dirs"][attrs.get("name", "")] = node
            stack.append(node)
        elif tag in ("int", "short", "long", "string", "float", "double") and "name" in attrs:
            v = attrs.get("value", "")
            try:
                v = int(v) if tag in ("int", "short", "long") else float(v) if tag in ("float", "double") else v
            except ValueError:
                pass
            stack[-1]["props"][attrs["name"]] = v

    def end(tag):
        if tag == "imgdir":
            stack.pop()

    p.StartElementHandler, p.EndElementHandler = start, end
    p.Parse(data, True)
    # the file's own wrapper <imgdir name="Check.img"> holds the entries
    top = root["dirs"]
    if len(top) == 1:
        only = next(iter(top.values()))
        if only["dirs"]:
            return only["dirs"]
    return top
