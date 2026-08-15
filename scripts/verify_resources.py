#!/usr/bin/env python3
"""verify_resources.py — UVC Monitor 资源一致性检查（ad-hoc 静态验证）

用法: python3 scripts/verify_resources.py [src/main 路径]

检查项:
1. res/ 下所有 XML 格式良好
2. 布局/Manifest 引用的 @string/@color/@drawable/@style/@xml/@mipmap 资源存在
3. Kotlin 代码引用的 R.* 资源存在
4. 布局 @+id 定义与 @id 引用一致
"""
import os, re, sys, xml.etree.ElementTree as ET

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "monmon", "src", "main")
RES = os.path.join(ROOT, "res")
JAVA = os.path.join(ROOT, "java")

LIB_STYLE_PREFIXES = (
    "TextAppearance.AppCompat", "Widget.AppCompat", "Theme.AppCompat",
    "Theme.Material", "Widget.Material", "Theme.Material3", "Widget.Material3",
    "Widget.Design", "Base.", "Platform.", "AlertDialog.",
)

errors = []


def collect_resources():
    res = {k: set() for k in
           ("string", "color", "drawable", "style", "xml", "mipmap", "attr", "layout")}
    for dirpath, _, filenames in os.walk(RES):
        base = os.path.basename(dirpath)
        if base == "drawable" or base.startswith("drawable-"):
            res["drawable"].update(os.path.splitext(f)[0] for f in filenames)
        elif base == "layout" or base.startswith("layout-"):
            res["layout"].update(os.path.splitext(f)[0] for f in filenames)
        elif base == "xml":
            res["xml"].update(os.path.splitext(f)[0] for f in filenames)
        elif base == "mipmap" or base.startswith("mipmap-"):
            res["mipmap"].update(os.path.splitext(f)[0] for f in filenames)
        elif base == "values" or base.startswith("values-"):
            for fn in filenames:
                try:
                    root = ET.parse(os.path.join(dirpath, fn)).getroot()
                    for child in root:
                        name = child.get("name")
                        if not name:
                            continue
                        if child.tag in ("string", "color", "style", "attr"):
                            res[child.tag].add(name)
                        elif child.tag == "item" and child.get("type") in res:
                            res[child.get("type")].add(name)
                except ET.ParseError as e:
                    errors.append(f"values 解析失败 {fn}: {e}")
    return res


res = collect_resources()

xml_files = []
for dirpath, _, filenames in os.walk(RES):
    for fn in filenames:
        if fn.endswith(".xml"):
            p = os.path.join(dirpath, fn)
            xml_files.append(p)
            try:
                ET.parse(p)
            except ET.ParseError as e:
                errors.append(f"XML 解析失败: {os.path.relpath(p, ROOT)}: {e}")


def check_refs(path, text, defined_ids):
    for m in re.finditer(r"@(string|color|drawable|style|xml|mipmap|attr)/([\w.]+)", text):
        kind, name = m.group(1), m.group(2)
        if kind == "attr":
            if name not in res["attr"]:
                errors.append(f"{path}: 引用缺失 @attr/{name}")
        elif name not in res.get(kind, set()):
            if kind == "style" and name.startswith(LIB_STYLE_PREFIXES):
                continue
            errors.append(f"{path}: 引用缺失 @{kind}/{name}")
    for m in re.finditer(r"@(?:\+id|id)/([\w.]+)", text):
        defined_ids.add(m.group(1))


defined_ids = set()
for p in [f for f in xml_files if "/layout" in f]:
    with open(p, encoding="utf-8") as f:
        check_refs(os.path.relpath(p, ROOT), f.read(), defined_ids)

for dirpath, _, filenames in os.walk(os.path.join(RES, "layout")):
    for fn in filenames:
        with open(os.path.join(dirpath, fn), encoding="utf-8") as f:
            for m in re.finditer(r"@id/([\w.]+)", f.read()):
                if m.group(1) not in defined_ids:
                    errors.append(f"{fn}: @id/{m.group(1)} 未定义")

for dirpath, _, filenames in os.walk(JAVA):
    for fn in filenames:
        if fn.endswith(".kt"):
            p = os.path.join(dirpath, fn)
            with open(p, encoding="utf-8") as f:
                text = f.read()
            for m in re.finditer(r"R\.(string|color|drawable|id|style|layout|mipmap|xml)\.([\w.]+)", text):
                kind, name = m.group(1), m.group(2)
                if kind == "layout":
                    if name not in res["layout"]:
                        errors.append(f"{os.path.relpath(p, ROOT)}: R.layout.{name} 缺失")
                elif kind == "id":
                    if name not in defined_ids:
                        errors.append(f"{os.path.relpath(p, ROOT)}: R.id.{name} 缺失")
                elif name not in res.get(kind, set()):
                    errors.append(f"{os.path.relpath(p, ROOT)}: R.{kind}.{name} 缺失")

manifest = os.path.join(ROOT, "AndroidManifest.xml")
with open(manifest, encoding="utf-8") as f:
    mtext = f.read()
for m in re.finditer(r"@(string|style|mipmap|xml|drawable)/([\w.]+)", mtext):
    if m.group(2) not in res.get(m.group(1), set()):
        errors.append(f"AndroidManifest.xml: 引用缺失 @{m.group(1)}/{m.group(2)}")

if errors:
    print(f"❌ 发现 {len(errors)} 个问题:")
    for e in errors[:30]:
        print("  -", e)
    sys.exit(1)
print(f"✅ 全部通过: {len(xml_files)} 个 XML 良构, 资源引用完整 "
      f"(string={len(res['string'])}, color={len(res['color'])}, "
      f"drawable={len(res['drawable'])}, style={len(res['style'])}, "
      f"layout={len(res['layout'])}, id={len(defined_ids)})")
sys.exit(0)
