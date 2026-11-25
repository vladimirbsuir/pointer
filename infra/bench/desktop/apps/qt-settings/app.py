import json
import os
import sys

from PySide6.QtCore import QTimer
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QFormLayout,
    QLineEdit,
    QPushButton,
    QSpinBox,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

CHECK_PATH = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qt-settings.json"

application = QApplication(sys.argv[:1])
window = QWidget()
window.setWindowTitle("Qt Settings")
window.setGeometry(100, 80, 480, 360)

tabs = QTabWidget()
general = QWidget()
general_layout = QFormLayout(general)
dark = QCheckBox("Dark mode")
font_size = QSpinBox()
font_size.setRange(8, 32)
font_size.setValue(12)
font_size.setAccessibleName("Font size")
general_layout.addRow(dark)
general_layout.addRow("Font size", font_size)
advanced = QWidget()
advanced_layout = QFormLayout(advanced)
proxy = QLineEdit()
proxy.setAccessibleName("Proxy")
advanced_layout.addRow("Proxy", proxy)
tabs.addTab(general, "General")
tabs.addTab(advanced, "Advanced")

apply_button = QPushButton("Apply")
applied = {"count": 0}


def write_state():
    snapshot = {
        "dark": dark.isChecked(),
        "fontSize": font_size.value(),
        "proxy": proxy.text(),
        "tab": tabs.currentIndex(),
        "applied": applied["count"],
    }
    temporary = CHECK_PATH + ".tmp"
    with open(temporary, "w", encoding="utf-8") as output:
        json.dump(snapshot, output)
    os.replace(temporary, CHECK_PATH)


def apply_settings():
    applied["count"] += 1
    write_state()


apply_button.clicked.connect(apply_settings)
tabs.currentChanged.connect(lambda _index: write_state())
layout = QVBoxLayout(window)
layout.addWidget(tabs)
layout.addWidget(apply_button)
window.show()
write_state()
QTimer.singleShot(0, lambda: print("qt-settings ready", flush=True))
application.exec()
