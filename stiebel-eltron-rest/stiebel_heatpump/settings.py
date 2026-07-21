"""Application settings.

Values may come from a YAML file (``--config`` / ``STIEBEL_APP_CONFIG``) and/or
environment variables prefixed with ``STIEBEL_``.
"""

from __future__ import annotations

from pathlib import Path
from typing import Literal, Optional

import yaml
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class AppSettings(BaseSettings):
    """Runtime configuration for the REST service."""

    model_config = SettingsConfigDict(env_prefix="STIEBEL_", extra="ignore")

    # Which heat pump protocol definition to expose (XML or YAML).
    device_config: str = Field(
        ...,
        description="Path to a channel-definition file (binding XML or native YAML).",
    )

    # Transport selection.
    transport: Literal["serial", "simulator"] = Field(
        "simulator",
        description="'serial' for a real USB device, 'simulator' for offline use.",
    )
    port: str = Field("/dev/ttyUSB0", description="Serial device path (serial transport).")
    baud_rate: int = Field(9600, description="Serial baud rate.")
    waiting_time_ms: int = Field(1200, description="Delay between serial requests in ms.")

    # API metadata.
    title: str = Field("Stiebel Eltron Heat Pump API", description="OpenAPI title.")
    connect_on_startup: bool = Field(True, description="Open the transport when the app starts.")

    @classmethod
    def load(cls, config_path: Optional[str] = None) -> "AppSettings":
        """Load settings from an optional YAML file, overlaid with env vars."""
        file_values: dict = {}
        path = config_path
        if path is None:
            import os

            path = os.environ.get("STIEBEL_APP_CONFIG")
        if path:
            with Path(path).open("r", encoding="utf-8") as handle:
                file_values = yaml.safe_load(handle) or {}
        return cls(**file_values)
