#!/usr/bin/env python3
"""
Parse a UI screenshot locally using OmniParser v2 models.
Runs on Apple Silicon (MPS), CUDA, or CPU — no GPU/internet required after first download.

First run downloads ~500 MB of models to ~/.local/lib/idea-ui-controller/models/.
Subsequent runs are fully offline.

Usage:
  omniparse.py <image_path> [--threshold N] [--device auto|mps|cpu|cuda] [--debug]

Output: JSON array of detected UI elements, each with:
  {id, type, label, x1, y1, x2, y2, cx, cy, w, h}
  All coordinates are in pixels relative to the input image.
"""
import sys
import json
import argparse
from pathlib import Path

MODELS_DIR = Path.home() / ".local" / "lib" / "idea-ui-controller" / "models"
YOLO_REPO  = "microsoft/OmniParser-v2.0"
YOLO_FILE  = "icon_detect/model.pt"
FLORENCE_REPO = "microsoft/Florence-2-base"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("image", help="Path to the PNG screenshot")
    parser.add_argument("--threshold", type=float, default=0.05,
                        help="YOLO confidence threshold (default: 0.05)")
    parser.add_argument("--device", default="auto",
                        help="Inference device: auto|mps|cpu|cuda (default: auto)")
    parser.add_argument("--debug", action="store_true")
    args = parser.parse_args()

    try:
        import torch
        from PIL import Image
    except ImportError as e:
        _die(f"Missing package: {e}. Re-run scripts/install.sh")

    device = _resolve_device(args.device)
    _log(f"Device: {device}")

    img_path = Path(args.image)
    if not img_path.exists():
        _die(f"Image not found: {img_path}")

    img = Image.open(img_path).convert("RGB")
    img_w, img_h = img.size

    boxes = _yolo_detect(img, args.threshold, device, args.debug)
    _log(f"Detected {len(boxes)} bounding boxes")

    if not boxes:
        print(json.dumps([]))
        return

    proc, fmodel = _load_florence(device)

    elements = []
    for i, (x1, y1, x2, y2, score, cls_name) in enumerate(boxes):
        x1i, y1i, x2i, y2i = int(x1), int(y1), int(x2), int(y2)
        crop = img.crop((x1i, y1i, x2i, y2i))
        if cls_name == "icon":
            label = _florence_caption(proc, fmodel, crop, device)
        else:
            label = _ocr(crop) or f"text {i}"
        elements.append({
            "id":  i,
            "type": cls_name,
            "label": label,
            "x1": x1i, "y1": y1i, "x2": x2i, "y2": y2i,
            "cx": (x1i + x2i) // 2,
            "cy": (y1i + y2i) // 2,
            "w":  x2i - x1i,
            "h":  y2i - y1i,
        })
        if args.debug:
            _log(f"  [{i}] {cls_name} score={score:.2f} label={label!r}")

    print(json.dumps(elements, indent=2))


# ---------- device ----------

def _resolve_device(spec):
    import torch
    if spec != "auto":
        return spec
    if torch.backends.mps.is_available():
        return "mps"
    if torch.cuda.is_available():
        return "cuda"
    return "cpu"


# ---------- YOLO detection ----------

def _yolo_detect(img, threshold, device, debug):
    try:
        from ultralytics import YOLO
    except ImportError:
        _die("ultralytics not installed — re-run scripts/install.sh")

    yolo_path = MODELS_DIR / "icon_detect" / "model.pt"
    if not yolo_path.exists():
        _log("Downloading YOLO detection model (~12 MB, first run only) …")
        try:
            from huggingface_hub import hf_hub_download
            hf_hub_download(
                repo_id=YOLO_REPO,
                filename=YOLO_FILE,
                local_dir=str(MODELS_DIR),
            )
        except Exception as e:
            _die(f"Model download failed: {e}")

    model = YOLO(str(yolo_path))
    results = model(img, conf=threshold, device=device, verbose=False)

    boxes = []
    if results and results[0].boxes is not None:
        for box in results[0].boxes:
            x1, y1, x2, y2 = box.xyxy[0].tolist()
            conf = float(box.conf[0])
            cls_id = int(box.cls[0])
            # OmniParser class map: 0 = icon, 1 = text
            cls_name = "icon" if cls_id == 0 else "text"
            boxes.append((x1, y1, x2, y2, conf, cls_name))
    return boxes


# ---------- Florence-2 captioning ----------

def _load_florence(device):
    import torch
    from transformers import AutoProcessor, AutoModelForCausalLM

    _log(f"Loading Florence-2-base (~460 MB on first run) …")
    proc = AutoProcessor.from_pretrained(FLORENCE_REPO, trust_remote_code=True)
    dtype = torch.float16 if device in ("mps", "cuda") else torch.float32
    fmodel = AutoModelForCausalLM.from_pretrained(
        FLORENCE_REPO,
        dtype=dtype,
        trust_remote_code=True,
        attn_implementation="eager",
    ).to(device).eval()
    _log("Florence-2 ready.")
    return proc, fmodel


def _florence_caption(proc, fmodel, crop_img, device):
    import torch
    task = "<CAPTION>"
    model_dtype = next(fmodel.parameters()).dtype
    inputs = proc(text=task, images=crop_img, return_tensors="pt")
    inputs = {
        k: (v.to(device=device, dtype=model_dtype) if v.is_floating_point() else v.to(device))
        for k, v in inputs.items()
    }
    with torch.no_grad():
        ids = fmodel.generate(
            input_ids=inputs["input_ids"],
            pixel_values=inputs["pixel_values"],
            max_new_tokens=32,
            num_beams=1,
            use_cache=False,
        )
    raw = proc.batch_decode(ids, skip_special_tokens=False)[0]
    parsed = proc.post_process_generation(raw, task=task, image_size=crop_img.size)
    return str(parsed.get(task, raw)).strip()


# ---------- OCR (best-effort for text regions) ----------

def _ocr(crop_img):
    # Try pytesseract first (optional install), fall back to a simple heuristic
    try:
        import pytesseract
        return pytesseract.image_to_string(crop_img, config="--psm 7").strip()
    except Exception:
        pass
    return ""


# ---------- helpers ----------

def _log(msg): print(msg, file=sys.stderr)
def _die(msg): print(f"error: {msg}", file=sys.stderr); sys.exit(1)


if __name__ == "__main__":
    main()
