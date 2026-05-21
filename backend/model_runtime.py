from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

import cv2
import numpy as np
from ultralytics import YOLO

try:
    import torch
except Exception:
    torch = None


@dataclass
class BlindInference:
    detected: bool
    guidance_text: str
    center_offset_ratio: float
    mask: Optional[np.ndarray]


@dataclass
class TrafficInference:
    light: str
    confidence: float
    raw_label: str = "unknown"
    stable_label: str = "unknown"


class BlindMaskStabilizer:
    def __init__(
        self,
        miss_ttl_max: int = 5,
        min_area_px: int = 1000,
        morph_kernel: int = 3,
        iou_high: float = 0.42,
        iou_low: float = 0.10,
    ) -> None:
        self.miss_ttl_max = miss_ttl_max
        self.min_area_px = min_area_px
        self.morph_kernel = morph_kernel
        self.iou_high = iou_high
        self.iou_low = iou_low
        self.prev_gray: Optional[np.ndarray] = None
        self.prev_stable_mask: Optional[np.ndarray] = None
        self.miss_ttl = miss_ttl_max

    def _binarize(self, mask: Optional[np.ndarray]) -> Optional[np.ndarray]:
        if mask is None:
            return None
        if mask.dtype != np.uint8:
            mask = mask.astype(np.uint8)
        return (mask > 0).astype(np.uint8) * 255

    def _morph(self, mask: Optional[np.ndarray]) -> Optional[np.ndarray]:
        if mask is None:
            return None
        k = cv2.getStructuringElement(
            cv2.MORPH_ELLIPSE, (max(1, self.morph_kernel), max(1, self.morph_kernel))
        )
        out = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, k, iterations=1)
        out = cv2.morphologyEx(out, cv2.MORPH_OPEN, k, iterations=1)
        return out

    @staticmethod
    def _iou(a: Optional[np.ndarray], b: Optional[np.ndarray]) -> float:
        if a is None or b is None:
            return 0.0
        inter = np.logical_and(a > 0, b > 0).sum()
        union = np.logical_or(a > 0, b > 0).sum()
        return float(inter) / float(union) if union > 0 else 0.0

    def _flow_warp(
        self, prev_mask: Optional[np.ndarray], prev_gray: Optional[np.ndarray], curr_gray: Optional[np.ndarray]
    ) -> Optional[np.ndarray]:
        if prev_mask is None or prev_gray is None or curr_gray is None:
            return None
        try:
            edge = cv2.Canny(prev_mask, 40, 120)
            p0 = cv2.goodFeaturesToTrack(
                prev_gray,
                maxCorners=220,
                qualityLevel=0.02,
                minDistance=6,
                blockSize=7,
                mask=edge,
            )
            if p0 is None or len(p0) < 8:
                p0 = cv2.goodFeaturesToTrack(
                    prev_gray,
                    maxCorners=180,
                    qualityLevel=0.02,
                    minDistance=6,
                    blockSize=7,
                    mask=(prev_mask > 0).astype(np.uint8) * 255,
                )
            if p0 is None or len(p0) < 8:
                return None

            p1, st, _ = cv2.calcOpticalFlowPyrLK(
                prev_gray,
                curr_gray,
                p0,
                None,
                winSize=(21, 21),
                maxLevel=3,
                criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 25, 0.01),
            )
            if p1 is None or st is None:
                return None

            good_old = p0[st == 1].reshape(-1, 2)
            good_new = p1[st == 1].reshape(-1, 2)
            if len(good_old) < 6 or len(good_new) < 6:
                return None

            m, _ = cv2.estimateAffinePartial2D(
                good_old,
                good_new,
                method=cv2.RANSAC,
                ransacReprojThreshold=5.0,
            )
            if m is None:
                return None

            h, w = curr_gray.shape[:2]
            return cv2.warpAffine(
                prev_mask,
                m,
                (w, h),
                flags=cv2.INTER_NEAREST,
                borderMode=cv2.BORDER_CONSTANT,
                borderValue=0,
            )
        except Exception:
            return None

    def stabilize(self, raw_mask: Optional[np.ndarray], curr_gray: Optional[np.ndarray]) -> Optional[np.ndarray]:
        curr = self._morph(self._binarize(raw_mask))
        prev = self._binarize(self.prev_stable_mask)

        if curr_gray is None:
            self.prev_stable_mask = curr
            return curr

        if prev is None or self.prev_gray is None:
            self.prev_stable_mask = curr
            self.prev_gray = curr_gray
            if curr is not None:
                self.miss_ttl = self.miss_ttl_max
            return curr

        area_curr = int(np.count_nonzero(curr)) if curr is not None else 0
        area_prev = int(np.count_nonzero(prev)) if prev is not None else 0
        has_curr = curr is not None and area_curr >= self.min_area_px

        if has_curr:
            iou = self._iou(curr, prev)
            fused = curr
            flow = None
            if iou > self.iou_low:
                flow = self._flow_warp(prev, self.prev_gray, curr_gray)
                flow = self._morph(self._binarize(flow))

            if flow is not None and iou < self.iou_high:
                w_curr = min(0.90, 0.40 + iou)
                mix = w_curr * curr.astype(np.float32) + (1.0 - w_curr) * flow.astype(np.float32)
                fused = (mix >= 128).astype(np.uint8) * 255
                fused = self._morph(fused)

            area_fused = int(np.count_nonzero(fused)) if fused is not None else 0
            if area_prev > 0 and iou < 0.20 and area_fused > int(area_prev * 1.35):
                fused = prev.copy()

            self.prev_stable_mask = fused
            self.prev_gray = curr_gray
            self.miss_ttl = self.miss_ttl_max
            return fused

        fallback = None
        if prev is not None and area_prev >= self.min_area_px and self.miss_ttl > 0:
            flow = self._flow_warp(prev, self.prev_gray, curr_gray)
            flow = self._morph(self._binarize(flow))
            area_flow = int(np.count_nonzero(flow)) if flow is not None else 0
            if area_flow >= int(self.min_area_px * 0.5):
                fallback = flow
            else:
                fallback = prev.copy()
            self.miss_ttl -= 1
        else:
            self.miss_ttl = 0

        self.prev_stable_mask = fallback
        self.prev_gray = curr_gray
        return fallback


class ModelRuntime:
    def __init__(self, blind_model: str, traffic_model: str, device: str = "cuda:0") -> None:
        self.blind_model_path = blind_model
        self.traffic_model_path = traffic_model
        self.requested_device = device
        self.device = "cpu"

        self.blind_model: Optional[YOLO] = None
        self.traffic_model: Optional[YOLO] = None
        self.blind_stabilizer = BlindMaskStabilizer()

        self._offset_ema = 0.0
        self._turn_ema = 0.0
        self._track_center_ema: Optional[float] = None
        self._track_dx_ema: float = 0.0
        self._branch_detected_recently: bool = False
        self._traffic_history: list[str] = []
        self._traffic_last_stable_label: str = "unknown"
        self._traffic_last_stable_ttl: int = 0
        self._traffic_unknown_streak: int = 0

    _TRAFFIC_FILTERED_CLASSES = {"crossing", "blank", "countdown_blank"}
    _TRAFFIC_VALID_LABELS = {"red", "green"}
    _TRAFFIC_HISTORY_SIZE = 6
    _TRAFFIC_MAJORITY = 3
    _TRAFFIC_STABLE_HOLD_FRAMES = 8
    _TRAFFIC_CONF_PASS1 = 0.45
    _TRAFFIC_CONF_PASS2 = 0.35
    _TRAFFIC_CONF_PASS3 = 0.30

    @staticmethod
    def _normalize_traffic_label(name: str) -> str:
        s = name.lower().strip()
        if "red" in s or "stop" in s:
            return "red"
        if "green" in s or "go" in s:
            return "green"
        if "off" in s:
            return "off"
        if "crossing" in s:
            return "crossing"
        if "blank" in s:
            return "blank"
        return "unknown"

    @staticmethod
    def _label_to_light(label: str) -> str:
        if label == "red":
            return "red"
        if label == "green":
            return "green"
        return "unknown"

    def _resolve_device(self) -> str:
        if self.requested_device.startswith("cuda"):
            if torch is not None and torch.cuda.is_available():
                return self.requested_device
            print(f"[BLIND][MODEL] requested {self.requested_device} but CUDA unavailable, fallback to CPU")
            return "cpu"
        return self.requested_device

    @staticmethod
    def _class_name(names: dict | list, cls_id: int) -> str:
        if isinstance(names, dict):
            return str(names.get(cls_id, cls_id))
        if isinstance(names, list) and 0 <= cls_id < len(names):
            return str(names[cls_id])
        return str(cls_id)

    def _tensor_to_mask(self, mask_tensor, out_w: int, out_h: int) -> np.ndarray:
        if torch is not None and isinstance(mask_tensor, torch.Tensor):
            t = mask_tensor
            if t.dtype in (torch.bfloat16, torch.float16):
                t = t.to(torch.float32)
            if t.ndim > 2:
                t = t.squeeze()
            t = (t > 0.5).to(torch.uint8).mul_(255)
            mask_u8 = t.detach().cpu().numpy()
        else:
            arr = np.asarray(mask_tensor)
            if arr.ndim > 2:
                arr = np.squeeze(arr)
            if arr.dtype != np.uint8:
                arr = (arr > 0.5).astype(np.uint8) * 255
            mask_u8 = arr

        if mask_u8.ndim == 3:
            mask_u8 = np.squeeze(mask_u8, axis=-1)
        if mask_u8.shape[1] != out_w or mask_u8.shape[0] != out_h:
            mask_u8 = cv2.resize(mask_u8, (out_w, out_h), interpolation=cv2.INTER_NEAREST)
        return mask_u8

    def _get_class_id_sets(self, names: dict | list) -> tuple[set[int], set[int]]:
        blind_ids: set[int] = set()
        cross_ids: set[int] = set()
        items = names.items() if isinstance(names, dict) else enumerate(names)
        for i, n in items:
            s = str(n).lower()
            if "blind" in s or "path" in s:
                blind_ids.add(int(i))
            if "cross" in s or "road_crossing" in s:
                cross_ids.add(int(i))
        return blind_ids, cross_ids

    @staticmethod
    def _split_runs(row_x: np.ndarray) -> list[tuple[int, int]]:
        if len(row_x) == 0:
            return []
        splits = np.where(np.diff(row_x) > 1)[0]
        starts = np.insert(splits + 1, 0, 0)
        ends = np.append(splits, len(row_x) - 1)
        runs: list[tuple[int, int]] = []
        for s_i, e_i in zip(starts, ends):
            runs.append((int(row_x[s_i]), int(row_x[e_i])))
        return runs

    def _tighten_blind_mask(self, mask: Optional[np.ndarray]) -> Optional[np.ndarray]:
        if mask is None:
            return None
        b = (mask > 0).astype(np.uint8) * 255
        h, w = b.shape[:2]
        if np.count_nonzero(b) < 100:
            return None

        b = cv2.morphologyEx(
            b,
            cv2.MORPH_CLOSE,
            cv2.getStructuringElement(cv2.MORPH_RECT, (3, 5)),
            iterations=1,
        )

        ys, xs = np.where(b > 0)
        if len(xs) == 0:
            return None

        bottom_idx = ys > int(h * 0.72)
        if int(np.count_nonzero(bottom_idx)) >= 20:
            anchor_x = float(np.median(xs[bottom_idx]))
        else:
            anchor_x = float(np.median(xs))

        if self._track_center_ema is None:
            prev_center = anchor_x
        else:
            prev_center = 0.7 * self._track_center_ema + 0.3 * anchor_x
        prev_dx = self._track_dx_ema

        out = np.zeros_like(b, dtype=np.uint8)
        max_band = int(w * 0.34)
        min_band = max(7, int(w * 0.03))
        max_shift_per_row = max(2.0, w * 0.02)

        branch_rows = 0
        valid_rows = 0
        last_y = float(h - 1)
        for y in range(h - 1, -1, -1):
            row_x = np.where(b[y] > 0)[0]
            if len(row_x) == 0:
                continue
            runs = self._split_runs(row_x)
            if not runs:
                continue

            candidates: list[tuple[float, int, int, float, int]] = []
            expected_center = prev_center + prev_dx
            for x0_raw, x1_raw in runs:
                width_raw = x1_raw - x0_raw + 1
                if width_raw < min_band:
                    continue

                x0 = x0_raw
                x1 = x1_raw
                width = width_raw
                if width > max_band:
                    c_raw = 0.5 * (x0 + x1)
                    x0 = int(max(0, c_raw - max_band * 0.5))
                    x1 = int(min(w - 1, c_raw + max_band * 0.5))
                    width = x1 - x0 + 1

                c = 0.5 * (x0 + x1)
                shift = abs(c - prev_center)
                direction_cost = abs(c - expected_center)
                width_penalty = width * 0.12
                horizontal_penalty = max(0.0, shift - max_shift_per_row) * 3.0
                score = direction_cost * 1.5 + abs(c - prev_center) + width_penalty + horizontal_penalty
                candidates.append((score, x0, x1, c, width))

            if not candidates:
                continue

            valid_rows += 1
            # Branch detection: two candidates close in score/size means fork.
            if len(candidates) >= 2:
                sorted_c = sorted(candidates, key=lambda t: t[0])
                c0 = sorted_c[0]
                c1 = sorted_c[1]
                if abs(c1[0] - c0[0]) < 20 and abs(c1[4] - c0[4]) < max(10, int(w * 0.05)):
                    branch_rows += 1

            best = min(candidates, key=lambda t: t[0])
            _, x0, x1, c, _ = best

            # If branch is active, tighten width further to suppress side branches.
            if branch_rows >= 2:
                cap_w = int(w * 0.22)
                center = 0.5 * (x0 + x1)
                x0 = int(max(0, center - cap_w * 0.5))
                x1 = int(min(w - 1, center + cap_w * 0.5))
                c = 0.5 * (x0 + x1)

            out[y, x0 : x1 + 1] = 255

            step = max(1.0, last_y - float(y))
            dx_now = (c - prev_center) / step
            prev_dx = 0.85 * prev_dx + 0.15 * dx_now
            prev_center = 0.85 * prev_center + 0.15 * c
            last_y = float(y)

        self._branch_detected_recently = branch_rows >= 2 and valid_rows > 10
        self._track_center_ema = prev_center
        self._track_dx_ema = prev_dx

        if np.count_nonzero(out) < 500:
            return None

        # Stronger suppression for cross-like spread at split points.
        if self._branch_detected_recently:
            out = cv2.erode(
                out,
                cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)),
                iterations=1,
            )
        out = cv2.morphologyEx(
            out,
            cv2.MORPH_CLOSE,
            cv2.getStructuringElement(cv2.MORPH_RECT, (3, 9)),
            iterations=1,
        )
        out = cv2.morphologyEx(
            out,
            cv2.MORPH_OPEN,
            cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)),
            iterations=1,
        )
        return out

    def _detect_path_and_crosswalk(self, image: np.ndarray) -> tuple[Optional[np.ndarray], Optional[np.ndarray]]:
        if self.blind_model is None:
            return None, None
        result = self.blind_model.predict(source=image, device=self.device, verbose=False, conf=0.25, imgsz=512)[0]

        masks_obj = getattr(result, "masks", None)
        if masks_obj is None or getattr(masks_obj, "data", None) is None:
            return None, None

        boxes = getattr(result, "boxes", None)
        names = getattr(result, "names", {}) or {}
        blind_ids, cross_ids = self._get_class_id_sets(names)

        data = masks_obj.data
        n = int(data.shape[0]) if hasattr(data, "shape") else len(data)
        h, w = image.shape[:2]
        blind_mask = np.zeros((h, w), dtype=np.uint8)
        cross_mask = np.zeros((h, w), dtype=np.uint8)

        cls_vals: list[int] = []
        if boxes is not None and getattr(boxes, "cls", None) is not None:
            cls_raw = boxes.cls
            if torch is not None and isinstance(cls_raw, torch.Tensor):
                cls_vals = [int(v) for v in cls_raw.detach().cpu().numpy().tolist()]
            else:
                cls_vals = [int(v) for v in np.asarray(cls_raw).tolist()]
        if not cls_vals:
            cls_vals = [-1] * n

        blind_candidates: list[np.ndarray] = []
        for i in range(min(n, len(cls_vals))):
            cls_id = cls_vals[i]
            m = self._tensor_to_mask(data[i], w, h)
            if cls_id in blind_ids:
                blind_candidates.append(m)
            elif cls_id in cross_ids:
                cross_mask = cv2.bitwise_or(cross_mask, m)

        for m in blind_candidates:
            blind_mask = cv2.bitwise_or(blind_mask, m)

        if np.count_nonzero(blind_mask) == 0 and n > 0:
            best_m = None
            best_score = -1e18
            anchor = np.zeros((h, w), dtype=np.uint8)
            anchor[int(h * 0.75) :, int(w * 0.38) : int(w * 0.62)] = 255
            for i in range(n):
                m = self._tensor_to_mask(data[i], w, h)
                area = int(np.count_nonzero(m))
                area_ratio = area / float(h * w)
                if area_ratio < 0.002 or area_ratio > 0.28:
                    continue
                overlap = int(np.count_nonzero(cv2.bitwise_and(m, anchor)))
                ys, xs = np.where(m > 0)
                if len(xs) < 20:
                    continue
                width = int(xs.max() - xs.min() + 1)
                score = overlap * 2.0 + area * 0.4 - width * 140
                if score > best_score:
                    best_score = score
                    best_m = m
            if best_m is not None:
                blind_mask = best_m

        blind_mask = self._tighten_blind_mask(blind_mask if np.count_nonzero(blind_mask) > 0 else None)
        cross_out = cross_mask if np.count_nonzero(cross_mask) > 0 else None
        return blind_mask, cross_out

    def _compute_blind_features(self, mask: np.ndarray) -> tuple[float, float]:
        h, w = mask.shape[:2]
        ys, xs = np.where(mask > 0)
        if len(xs) < 80:
            return 0.0, 0.0

        def _median_x(y_low: float, y_high: float, fallback: float) -> float:
            lo = int(h * y_low)
            hi = int(h * y_high)
            idx = (ys >= lo) & (ys < hi)
            if int(np.count_nonzero(idx)) < 20:
                return fallback
            return float(np.median(xs[idx]))

        cx_global = float(np.median(xs))
        cx_bottom = _median_x(0.65, 1.0, cx_global)
        cx_top = _median_x(0.20, 0.55, cx_global)
        offset = (cx_bottom - (w * 0.5)) / (w * 0.5)
        turn = (cx_top - cx_bottom) / (w * 0.5)
        return float(offset), float(turn)

    def _guidance_from_features(self, offset_ratio: float, turn_ratio: float) -> str:
        self._offset_ema = 0.70 * self._offset_ema + 0.30 * offset_ratio
        self._turn_ema = 0.70 * self._turn_ema + 0.30 * turn_ratio
        off = self._offset_ema
        turn = self._turn_ema

        if abs(off) > 0.24:
            return "请向左转动。" if off < 0 else "请向右转动。"
        if abs(off) > 0.12:
            return "请向左微调，对准盲道。" if off < 0 else "请向右微调，对准盲道。"
        if turn < -0.18:
            return "请向左转动。"
        if turn > 0.18:
            return "请向右转动。"
        return "保持直行"

    def load(self) -> None:
        self.device = self._resolve_device()
        self.blind_model = YOLO(self.blind_model_path)
        self.traffic_model = YOLO(self.traffic_model_path)

        warm = np.zeros((384, 640, 3), dtype=np.uint8)
        blind_res = self.blind_model.predict(source=warm, device=self.device, verbose=False, conf=0.25, imgsz=512)[0]
        traffic_res = self.traffic_model.predict(source=warm, device=self.device, verbose=False, conf=0.25, imgsz=512)[0]
        print(
            f"[BLIND][MODEL] blind={self.blind_model_path} device={self.device} "
            f"classes={getattr(blind_res, 'names', {})}"
        )
        print(
            f"[BLIND][MODEL] traffic={self.traffic_model_path} device={self.device} "
            f"classes={getattr(traffic_res, 'names', {})}"
        )
        print("[BLIND][MODEL] warmup ok")

    def infer_blind(self, bgr: np.ndarray) -> BlindInference:
        try:
            blind_raw, _ = self._detect_path_and_crosswalk(bgr)
        except Exception:
            return BlindInference(False, "丢失路径，重新搜索。", 0.0, None)

        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        stable = self.blind_stabilizer.stabilize(blind_raw, gray)
        if stable is None:
            return BlindInference(False, "丢失路径，重新搜索。", 0.0, None)

        h, w = stable.shape[:2]
        area = int(np.count_nonzero(stable))
        if area < max(700, int(h * w * 0.0015)):
            return BlindInference(False, "丢失路径，重新搜索。", 0.0, None)

        offset, turn = self._compute_blind_features(stable)
        return BlindInference(True, self._guidance_from_features(offset, turn), offset, stable)

    def infer_traffic(self, bgr: np.ndarray) -> TrafficInference:
        if self.traffic_model is None:
            return TrafficInference("unknown", 0.0, "unknown", "unknown")
        def _predict(frame: np.ndarray, conf_thr: float, imgsz: int):
            try:
                return self.traffic_model.predict(
                    source=frame,
                    device=self.device,
                    verbose=False,
                    conf=conf_thr,
                    imgsz=imgsz,
                )[0]
            except Exception:
                return None

        def _extract_candidates(result_obj) -> list[tuple[str, float]]:
            if result_obj is None:
                return []
            boxes = getattr(result_obj, "boxes", None)
            names = getattr(result_obj, "names", {}) or {}
            if boxes is None or getattr(boxes, "cls", None) is None or len(boxes) == 0:
                return []
            cls_raw = boxes.cls
            conf_raw = boxes.conf
            if torch is not None and isinstance(cls_raw, torch.Tensor):
                cls_vals = cls_raw.detach().cpu().numpy().tolist()
            else:
                cls_vals = np.asarray(cls_raw).tolist()
            if torch is not None and isinstance(conf_raw, torch.Tensor):
                conf_vals = conf_raw.detach().cpu().numpy().tolist()
            else:
                conf_vals = np.asarray(conf_raw).tolist()
            out: list[tuple[str, float]] = []
            for cls_v, conf in zip(cls_vals, conf_vals):
                name = self._class_name(names, int(cls_v))
                norm = self._normalize_traffic_label(name)
                if norm in self._TRAFFIC_FILTERED_CLASSES:
                    continue
                out.append((norm, float(conf)))
            out.sort(key=lambda x: x[1], reverse=True)
            return out

        def _enhance_for_red_scene(frame: np.ndarray) -> np.ndarray:
            # Improve dark/overexposed lamp scenes for red-light recall.
            hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
            h, s, v = cv2.split(hsv)
            clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
            v = clahe.apply(v)
            s = cv2.addWeighted(s, 1.1, s, 0.0, 0.0)
            hsv2 = cv2.merge((h, s, v))
            return cv2.cvtColor(hsv2, cv2.COLOR_HSV2BGR)

        def _center_roi(frame: np.ndarray) -> np.ndarray:
            # Traffic light is usually near center/top in mobile preview.
            h, w = frame.shape[:2]
            x0 = max(0, int(w * 0.10))
            x1 = min(w, int(w * 0.90))
            y0 = max(0, int(h * 0.05))
            y1 = min(h, int(h * 0.85))
            roi = frame[y0:y1, x0:x1]
            if roi.size == 0:
                return frame
            return roi

        # Pass-1: stricter standard inference to reduce false positives.
        candidates = _extract_candidates(_predict(bgr, conf_thr=self._TRAFFIC_CONF_PASS1, imgsz=640))
        # Pass-2: retry with enhanced image if still unknown.
        if not any(lbl in self._TRAFFIC_VALID_LABELS for lbl, _ in candidates):
            enhanced = _enhance_for_red_scene(bgr)
            retry = _extract_candidates(_predict(enhanced, conf_thr=self._TRAFFIC_CONF_PASS2, imgsz=736))
            if retry:
                candidates = retry
        # Pass-3: center ROI retry for close-up lights.
        if not any(lbl in self._TRAFFIC_VALID_LABELS for lbl, _ in candidates):
            roi = _center_roi(bgr)
            roi_enh = _enhance_for_red_scene(roi)
            retry_roi = _extract_candidates(_predict(roi_enh, conf_thr=self._TRAFFIC_CONF_PASS3, imgsz=896))
            if retry_roi:
                candidates = retry_roi

        detected_label = "unknown"
        detected_conf = 0.0
        for label, conf in candidates:
            if label in self._TRAFFIC_VALID_LABELS:
                detected_label = label
                detected_conf = conf
                break

        # Majority vote across recent frames for stable label.
        self._traffic_history.append(detected_label)
        if len(self._traffic_history) > self._TRAFFIC_HISTORY_SIZE:
            self._traffic_history.pop(0)

        stable_label = "unknown"
        valid_recent = [x for x in self._traffic_history if x in self._TRAFFIC_VALID_LABELS]
        if len(valid_recent) >= self._TRAFFIC_MAJORITY:
            counter: dict[str, int] = {}
            for x in valid_recent:
                counter[x] = counter.get(x, 0) + 1
            label, hits = max(counter.items(), key=lambda kv: kv[1])
            if hits >= self._TRAFFIC_MAJORITY:
                stable_label = label
                self._traffic_last_stable_label = label
                self._traffic_last_stable_ttl = self._TRAFFIC_STABLE_HOLD_FRAMES

        if stable_label == "unknown" and self._traffic_last_stable_ttl > 0:
            if self._traffic_last_stable_label in self._TRAFFIC_VALID_LABELS:
                stable_label = self._traffic_last_stable_label
                self._traffic_last_stable_ttl -= 1

        if detected_label == "unknown":
            self._traffic_unknown_streak += 1
            if self._traffic_unknown_streak % 20 == 1:
                top = candidates[0] if candidates else ("none", 0.0)
                print(
                    f"[BLIND][TRAFFIC] unknown streak={self._traffic_unknown_streak} "
                    f"top_candidate={top[0]} conf={top[1]:.2f}"
                )
        else:
            self._traffic_unknown_streak = 0

        # Prefer stable label, but fall back to current-frame label for faster response.
        chosen_label = stable_label if stable_label in self._TRAFFIC_VALID_LABELS else detected_label
        light = self._label_to_light(chosen_label)
        return TrafficInference(light, detected_conf, raw_label=detected_label, stable_label=stable_label)

