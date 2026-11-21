"""Build the two Studio distributions of the OFL Allo v1.0.3 variable font."""
import argparse
from pathlib import Path
from hashlib import sha256
from shutil import copyfile
from tempfile import TemporaryDirectory

from fontTools.ttLib import TTFont
from fontTools.merge import Merger
from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.subset import Options, Subsetter
from fontTools.varLib.instancer import instantiateVariableFont

SOURCE_SHA256 = "cdc994466d3a2dc77d5f72bd4f60c1bbf4d65dad5901ac064c7cc04e182c4fe4"
WEIGHTS = {400: "Regular", 500: "Medium", 600: "SemiBold", 700: "Bold"}


def name_font(font, style, *, family="Allo Round 100", variable=False):
    # Distinguish the baked derivative from the upstream ROND=50 builds and other installed faces.
    postscript = family.replace(" ", "")
    legacy_family = family if style in ("Regular", "Bold") else f"{family} {style}"
    names = {
        1: legacy_family,
        2: "Regular" if style not in ("Regular", "Bold") else style,
        3: f"{font['name'].getDebugName(5)};Studio;{postscript}-{style}",
        4: f"{family} {style}",
        6: f"AlloRound100-{style}",
        16: family,
        17: style,
    }
    if variable:
        names[25] = postscript
    for key, value in names.items():
        for record in list(font["name"].names):
            if record.nameID == key:
                font["name"].removeNames(nameID=key, platformID=record.platformID,
                                         platEncID=record.platEncID, langID=record.langID)
        font["name"].setName(value, key, 3, 1, 0x409)
        font["name"].setName(value, key, 0, 4, 0)


def style_flags(font, weight):
    # FontTools sets usWeightClass, but the original default's Regular style bit
    # would otherwise be left on the new static weights, including Bold.
    os2 = font["OS/2"]
    os2.fsSelection &= ~((1 << 5) | (1 << 6))
    if weight == 400:
        os2.fsSelection |= 1 << 6
    if weight == 700:
        os2.fsSelection |= 1 << 5
    font["head"].macStyle = (font["head"].macStyle & ~1) | int(weight == 700)


def swing_font(allo_path, inter_path, weight, scratch):
    # AWT accepts one physical UI font. Keep Allo's full cmap, then add the
    # complement from Inter. Subsetting retains components/OT layout closure;
    # no Unicode code point is left encoded in both inputs to the merge.
    allo = TTFont(allo_path, recalcTimestamp=False)
    inter = instantiateVariableFont(TTFont(inter_path, recalcTimestamp=False),
                                    {"wght": weight, "opsz": 14})
    inter.flavor = None
    allo_chars = set(allo.getBestCmap())
    only_inter = set(inter.getBestCmap()) - allo_chars
    options = Options()
    options.layout_features = ["*"]
    subsetter = Subsetter(options=options)
    subsetter.populate(unicodes=only_inter)
    subsetter.subset(inter)
    parts = [scratch / "allo.ttf", scratch / "inter.ttf"]
    for part, path in zip((allo, inter), parts):
        part["post"].formatType = 3.0  # Keep glyph names internal to this standalone face.
        part.save(path)
    merged = Merger().merge([str(p) for p in parts])
    merged.recalcTimestamp = False
    assert set(merged.getBestCmap()) == allo_chars | only_inter
    name_font(merged, WEIGHTS[weight], family="Studio Allo Inter Round 100")
    style_flags(merged, weight)
    return merged


def outlines(font, text):
    cmap = font.getBestCmap()
    glyphs = font.getGlyphSet()
    result = []
    for character in text:
        glyph = glyphs[cmap[ord(character)]]
        pen = DecomposingRecordingPen(glyphs)
        glyph.draw(pen)
        result.append((pen.value, glyph.width))
    return result


def max_delta(left, right):
    if isinstance(left, (tuple, list)) and isinstance(right, (tuple, list)):
        assert len(left) == len(right)
        return max((max_delta(a, b) for a, b in zip(left, right)), default=0)
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return abs(left - right)
    assert left == right  # Same sequence of contour operations.
    return 0


def verify(source, web_dir, native_dir, inter_dir):
    """Check the shipped outlines, not their names or the generator's arguments."""
    web = TTFont(web_dir / "Allo-Rond100.woff2")
    inter = TTFont(inter_dir / "InterVariable.woff2")
    assert {axis.axisTag for axis in web["fvar"].axes} == {"opsz", "wght"}
    allo_chars = set(source.getBestCmap())
    inter_chars = set(inter.getBestCmap())
    assert set(web.getBestCmap()) == allo_chars
    latin, fallback = "Voxarium Studio", "СтудияЁжΩ"
    assert set(map(ord, fallback)) <= inter_chars - allo_chars
    for weight in WEIGHTS:
        location = {"wght": weight, "opsz": 14}
        round100 = outlines(instantiateVariableFont(source, {**location, "ROND": 100}), latin)
        round50 = outlines(instantiateVariableFont(source, {**location, "ROND": 50}), latin)
        native = TTFont(native_dir / f"Allo-Rond100-{weight}.ttf")
        assert "fvar" not in native and native["OS/2"].usWeightClass == weight
        assert set(native.getBestCmap()) == allo_chars
        variants = {
            "web": outlines(instantiateVariableFont(web, location), latin),
            "native": outlines(native, latin),
        }
        if weight in (400, 700):
            swing = TTFont(native_dir / f"Allo-Inter-Rond100-{weight}.ttf")
            assert "fvar" not in swing and swing["OS/2"].usWeightClass == weight
            assert set(swing.getBestCmap()) == allo_chars | inter_chars
            assert max_delta(outlines(swing, fallback),
                             outlines(instantiateVariableFont(inter, location), fallback)) <= 1
            variants["Swing"] = outlines(swing, latin)
        # Font serialization rounds coordinates; allow one TrueType font unit
        # of quantization, out of 2048 per em. ROND=50 differs by dozens.
        assert max_delta(round100, round50) >= 20
        for name, outlines_at_weight in variants.items():
            delta = max_delta(round100, outlines_at_weight)
            assert delta <= 1, (name, weight, delta)
            print(f"{name} {weight}: ROND=100 delta {delta:.3f}; "
                  f"ROND=50 control delta {max_delta(round100, round50):.3f}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="fonts/variable/AlloVariable.ttf from Allo-1.0.3.zip")
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="Studio checkout")
    parser.add_argument("--temp", type=Path, help="directory for disposable merge inputs")
    parser.add_argument("--verify", action="store_true", help="verify existing assets without writing")
    args = parser.parse_args()
    if sha256(args.source.read_bytes()).hexdigest() != SOURCE_SHA256:
        parser.error("unexpected upstream TTF SHA-256 (expected Allo v1.0.3)")
    web_dir = args.root / "frontend/public/fonts/allo"
    native_dir = args.root / "desktop-native/src/main/resources/fonts"
    inter_dir = args.root / "frontend/public/fonts/inter"
    source = TTFont(args.source, recalcTimestamp=False)
    if args.verify:
        verify(source, web_dir, native_dir, inter_dir)
        return
    if args.temp is None:
        parser.error("--temp is required unless using --verify")
    for directory in (web_dir, native_dir, args.temp):
        directory.mkdir(parents=True, exist_ok=True)
    assert next(axis for axis in source["fvar"].axes if axis.axisTag == "ROND").maxValue == 100
    web = instantiateVariableFont(source, {"ROND": 100})
    name_font(web, "Regular", variable=True)
    web.flavor = "woff2"
    web.save(web_dir / "Allo-Rond100.woff2")
    for weight, style in WEIGHTS.items():
        native = instantiateVariableFont(source, {"ROND": 100, "opsz": 14, "wght": weight})
        name_font(native, style)
        style_flags(native, weight)
        native.save(native_dir / f"Allo-Rond100-{weight}.ttf")
    with TemporaryDirectory(dir=args.temp) as scratch:
        for weight in (400, 700):
            merged = swing_font(native_dir / f"Allo-Rond100-{weight}.ttf",
                                inter_dir / "InterVariable.woff2", weight, Path(scratch))
            merged.save(native_dir / f"Allo-Inter-Rond100-{weight}.ttf")
    # The upstream release zip places OFL.txt two directories above the variable TTF.
    copyfile(args.source.parents[2] / "OFL.txt", web_dir / "OFL.txt")
    copyfile(args.source.parents[2] / "OFL.txt", native_dir / "Allo-OFL.txt")
    copyfile(inter_dir / "LICENSE.txt", native_dir / "Inter-OFL.txt")


if __name__ == "__main__":
    main()

