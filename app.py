import importlib.util
from pathlib import Path

server_file = Path(__file__).resolve().parent / "server" / "app.py"
spec = importlib.util.spec_from_file_location("vynix_server_app", server_file)
if spec is None or spec.loader is None:
    raise RuntimeError("Cannot load VYNIX API module")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
app = module.app
