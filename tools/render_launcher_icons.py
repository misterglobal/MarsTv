"""Export existing MarsTV vector artwork as launcher PNGs (pip install resvg-py==0.5.0)."""
from pathlib import Path
import xml.etree.ElementTree as ET
import resvg_py

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"


def render(svg: str, target: Path, width: int, height: int):
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(resvg_py.svg_to_bytes(svg_string=svg, width=width, height=height))


def main():
    icon = (ROOT / "brand/marstv-icon.svg").read_text(encoding="utf-8")
    for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        for name in ("ic_launcher", "ic_launcher_round"):
            render(icon, RES / f"mipmap-{density}/{name}.png", size, size)
            # Replace the original Android template WebP resources, avoiding duplicate IDs.
            (RES / f"mipmap-{density}/{name}.webp").unlink(missing_ok=True)
    render(icon, RES / "mipmap-nodpi/ic_launcher_legacy.png", 512, 512)

    # Rasterize the existing TV vector, preserving its artwork and 16:9 aspect ratio.
    vector = ET.parse(RES / "drawable/tv_banner.xml").getroot()
    android = "{http://schemas.android.com/apk/res/android}"
    svg = ET.Element("svg", {"xmlns": "http://www.w3.org/2000/svg", "viewBox": "0 0 320 180"})
    for path in vector.findall("path"):
        color = path.attrib[android + "fillColor"]
        attributes = {"d": path.attrib[android + "pathData"], "fill": color}
        if len(color) == 9:
            attributes.update(fill="#" + color[3:], **{"fill-opacity": str(int(color[1:3], 16) / 255)})
        ET.SubElement(svg, "path", attributes)
    render(ET.tostring(svg, encoding="unicode"), RES / "drawable-xhdpi/tv_launcher_banner.png", 320, 180)


if __name__ == "__main__":
    main()
