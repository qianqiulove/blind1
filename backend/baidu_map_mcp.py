from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from html import unescape
from typing import Any
from urllib.parse import urlencode
from urllib.request import urlopen


@dataclass(frozen=True)
class BaiduMapEndpoints:
    geocoder: str = "https://api.map.baidu.com/geocoding/v3/"
    reverse_geocoder: str = "https://api.map.baidu.com/reverse_geocoding/v3/"
    place_search: str = "https://api.map.baidu.com/place/v2/search"
    direction_base: str = "https://api.map.baidu.com/directionlite/v1/"


class BaiduMapMCP:
    def __init__(self, api_key: str, timeout_sec: float = 8.0) -> None:
        self.api_key = api_key.strip()
        self.timeout_sec = timeout_sec
        self.endpoints = BaiduMapEndpoints()

    def _request_json(self, url: str, params: dict[str, Any]) -> dict[str, Any]:
        q = params.copy()
        q["ak"] = self.api_key
        full = f"{url}?{urlencode(q)}"
        with urlopen(full, timeout=self.timeout_sec) as resp:
            body = resp.read().decode("utf-8", errors="ignore")
        return json.loads(body)

    @staticmethod
    def _error_message(status_code: int, default: str) -> str:
        table = {
            1: "server internal error",
            2: "invalid request parameters",
            3: "permission check failed",
            4: "quota check failed",
            5: "invalid or missing AK",
            101: "service forbidden",
            102: "ip whitelist or sn check failed",
            200: "no permission",
            300: "quota error",
            301: "AK quota exceeded",
            302: "daily quota exceeded",
            210: "origin not found",
            220: "destination not found",
        }
        return table.get(status_code, default or "unknown error")

    def geocoding(self, address: str, city: str | None = None) -> dict[str, Any]:
        params: dict[str, Any] = {
            "address": address,
            "output": "json",
            "ret_coordtype": "bd09ll",
        }
        if city:
            params["city"] = city
        try:
            data = self._request_json(self.endpoints.geocoder, params)
            if data.get("status") == 0:
                result = data.get("result", {})
                location = result.get("location", {})
                return {
                    "success": True,
                    "lat": location.get("lat"),
                    "lng": location.get("lng"),
                    "confidence": result.get("confidence", 0),
                    "precise": result.get("precise", 0),
                    "level": result.get("level", ""),
                    "coordtype": "bd09ll",
                }
            code = int(data.get("status", -1))
            return {
                "success": False,
                "status_code": code,
                "error": self._error_message(code, str(data.get("message", "geocoding failed"))),
            }
        except Exception as e:
            return {"success": False, "error": f"geocoding exception: {e}"}

    def reverse_geocoding(self, lat: float, lng: float, coordtype: str = "bd09ll") -> dict[str, Any]:
        params = {
            "location": f"{lat},{lng}",
            "output": "json",
            "coordtype": coordtype,
            "extensions_poi": 0,
        }
        try:
            data = self._request_json(self.endpoints.reverse_geocoder, params)
            if data.get("status") == 0:
                result = data.get("result", {})
                return {
                    "success": True,
                    "formatted_address": result.get("formatted_address", ""),
                    "business": result.get("business", ""),
                    "address_components": result.get("addressComponent", {}),
                    "sematic_description": result.get("sematic_description", ""),
                    "coordtype": coordtype,
                }
            code = int(data.get("status", -1))
            return {
                "success": False,
                "status_code": code,
                "error": self._error_message(code, str(data.get("message", "reverse geocoding failed"))),
            }
        except Exception as e:
            return {"success": False, "error": f"reverse geocoding exception: {e}"}

    def search_nearby_places(self, query: str, lat: float, lng: float, radius: int = 1000) -> dict[str, Any]:
        params = {
            "query": query,
            "location": f"{lat},{lng}",
            "radius": radius,
            "output": "json",
        }
        try:
            data = self._request_json(self.endpoints.place_search, params)
            if data.get("status") == 0:
                places = []
                for p in data.get("results", []) or []:
                    loc = p.get("location", {})
                    d = (p.get("detail_info") or {}).get("distance")
                    if d in (None, "", 0, "0"):
                        try:
                            plat = float(loc.get("lat"))
                            plng = float(loc.get("lng"))
                            d = int(self._haversine_m(lat, lng, plat, plng))
                        except Exception:
                            d = None
                    places.append(
                        {
                            "name": p.get("name", ""),
                            "address": p.get("address", ""),
                            "lat": loc.get("lat"),
                            "lng": loc.get("lng"),
                            "distance": d,
                        }
                    )
                return {"success": True, "total": len(places), "places": places}
            code = int(data.get("status", -1))
            return {
                "success": False,
                "status_code": code,
                "error": self._error_message(code, str(data.get("message", "search places failed"))),
            }
        except Exception as e:
            return {"success": False, "error": f"search places exception: {e}"}

    def calculate_route(
        self,
        origin_lat: float,
        origin_lng: float,
        dest_lat: float,
        dest_lng: float,
        mode: str = "walking",
        coordtype: str = "bd09ll",
    ) -> dict[str, Any]:
        mode = mode.lower().strip()
        endpoint = {
            "walking": "walking",
            "driving": "driving",
            "transit": "transit",
            "riding": "riding",
        }.get(mode, "walking")
        url = self.endpoints.direction_base + endpoint
        params = {
            "origin": f"{origin_lat},{origin_lng}",
            "destination": f"{dest_lat},{dest_lng}",
            "coord_type": coordtype,
            "output": "json",
        }
        try:
            data = self._request_json(url, params)
            if data.get("status") == 0:
                routes = (data.get("result") or {}).get("routes") or []
                if not routes:
                    return {"success": False, "error": "no route found"}
                route = routes[0]
                steps_raw = route.get("steps") or []
                steps: list[str] = []
                for s in steps_raw:
                    text = s.get("instruction") or s.get("instructions") or ""
                    text = unescape(re.sub(r"<[^>]*>", "", str(text))).strip()
                    if text:
                        steps.append(text)
                return {
                    "success": True,
                    "mode": endpoint,
                    "coordtype": coordtype,
                    "distance": route.get("distance", 0),
                    "duration": route.get("duration", 0),
                    "steps": steps,
                }
            code = int(data.get("status", -1))
            return {
                "success": False,
                "status_code": code,
                "error": self._error_message(code, str(data.get("message", "route planning failed"))),
            }
        except Exception as e:
            return {"success": False, "error": f"route planning exception: {e}"}

    @staticmethod
    def _haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
        r = 6371000.0
        p1 = math.radians(lat1)
        p2 = math.radians(lat2)
        dp = math.radians(lat2 - lat1)
        dl = math.radians(lng2 - lng1)
        a = math.sin(dp / 2.0) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2.0) ** 2
        c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
        return r * c
