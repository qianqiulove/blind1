from __future__ import annotations

import argparse
from pathlib import Path

import cv2
from ultralytics import YOLO


TRAFFIC_FILTERED_CLASSES = {"crossing", "blank", "countdown_blank"}
TRAFFIC_VALID_LABELS = {"stop", "go", "countdown_go", "countdown_stop"}


def normalize_traffic_label(name: str) -> str:
    s = name.lower().strip()
    if "countdown_stop" in s:
        return "countdown_stop"
    if "countdown_go" in s:
        return "countdown_go"
    if "stop" in s or "red" in s:
        return "stop"
    if "go" in s or "green" in s:
        return "go"
    if "yellow" in s:
        return "countdown_go"
    if "crossing" in s:
        return "crossing"
    if "blank" in s:
        return "blank"
    return "unknown"


def label_to_light(label: str) -> str:
    if label in ("stop", "countdown_stop"):
        return "red"
    if label in ("go", "countdown_go"):
        return "green"
    return "unknown"


def main() -> int:
    p = argparse.ArgumentParser(description="Single-image test for trafficlight.pt")
    p.add_argument("--image", required=True, help="Path to image file")
    p.add_argument("--model", default=r"E:\OpenAIglasses_for_Navigation-main\blind\models\trafficlight.pt")
    p.add_argument("--device", default="cuda:0")
    p.add_argument("--conf", type=float, default=0.08)
    p.add_argument("--imgsz", type=int, default=896)
    p.add_argument("--save", action="store_true", help="Save rendered image to blind/runs/traffic_test")
    args = p.parse_args()

    image_path = Path(args.image)
    model_path = Path(args.model)
    if not image_path.exists():
        print(f"[ERR] image not found: {image_path}")
        return 1
    if not model_path.exists():
        print(f"[ERR] model not found: {model_path}")
        return 1

    model = YOLO(str(model_path))
    source = cv2.imread(str(image_path))
    if source is None:
        print(f"[ERR] cannot decode image: {image_path}")
        return 1

    result = model.predict(
        source=source,
        device=args.device,
        conf=args.conf,
        imgsz=args.imgsz,
        verbose=False,
        save=args.save,
        project=r"E:\OpenAIglasses_for_Navigation-main\blind\runs",
        name="traffic_test",
        exist_ok=True,
    )[0]

    boxes = getattr(result, "boxes", None)
    names = getattr(result, "names", {}) or {}
    if boxes is None or getattr(boxes, "cls", None) is None or len(boxes) == 0:
        print("[RESULT] no boxes")
        print("[RESULT] raw=unknown light=unknown")
        return 0

    cls_vals = boxes.cls.detach().cpu().numpy().tolist()
    conf_vals = boxes.conf.detach().cpu().numpy().tolist()

    candidates: list[tuple[str, float, str]] = []
    for cls_v, conf in zip(cls_vals, conf_vals):
        name = str(names.get(int(cls_v), cls_v)) if isinstance(names, dict) else str(cls_v)
        norm = normalize_traffic_label(name)
        if norm in TRAFFIC_FILTERED_CLASSES:
            continue
        candidates.append((norm, float(conf), name))

    candidates.sort(key=lambda x: x[1], reverse=True)

    print(f"[RESULT] total_boxes={len(cls_vals)} valid_candidates={len(candidates)}")
    for i, (norm, conf, raw_name) in enumerate(candidates[:10], start=1):
        print(f"  {i}. raw_name={raw_name} norm={norm} conf={conf:.3f}")

    detected_label = "unknown"
    detected_conf = 0.0
    for label, conf, _ in candidates:
        if label in TRAFFIC_VALID_LABELS:
            detected_label = label
            detected_conf = conf
            break

    light = label_to_light(detected_label)
    print(f"[RESULT] raw={detected_label} conf={detected_conf:.3f} light={light}")
    if args.save:
        print(r"[RESULT] saved image: E:\OpenAIglasses_for_Navigation-main\blind\runs\traffic_test")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

