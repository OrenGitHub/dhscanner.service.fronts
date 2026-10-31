import ast
import sys
import json
import typing
import logging
import fastapi
import urllib.parse
from fastapi import status

import rewrite

logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] %(message)s",
    datefmt="%d/%m/%Y ( %H:%M:%S )",
    stream=sys.stdout,
    force=True
)

app = fastapi.FastAPI()

class ReplaceQuotes(ast.NodeTransformer):
    def visit_Constant(self, node: ast.Constant) -> ast.Constant:
        if isinstance(node.value, str):
            node.value = node.value.replace('\'', '')
            node.value = node.value.replace('"', '')
        return node

def validated_source(source: fastapi.UploadFile = fastapi.File(...)) -> fastapi.UploadFile:
    if source.filename is None:
        raise fastapi.HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Filename is required in the file upload"
        )
    return source

@app.get('/healthcheck')
def healthcheck():
    return { 'healthy': True }

@app.post('/to/native/py/ast')
async def parse(
    source: fastapi.UploadFile = fastapi.Depends(validated_source),
    python_files: typing.Optional[str] = fastapi.Form(None),
    python_classes: typing.Optional[str] = fastapi.Form(None),
    python_functions: typing.Optional[str] = fastapi.Form(None)
):
    decoded_filename = urllib.parse.unquote(source.filename)
    logging.info(f'POST {decoded_filename}')
    content = await source.read()
    python_source_code = content.decode()
    original_tree = ast.parse(python_source_code)
    # `python_files` : json list of the scanned `.py` files,
    # `python_classes` / `python_functions` : json { file : [ top level names ] } ( runner #131 )
    if python_files:
        classes = json.loads(python_classes) if python_classes else None
        functions = json.loads(python_functions) if python_functions else None
        original_tree = rewrite.rewrite(original_tree, json.loads(python_files), classes, functions)
    normalized_tree = ReplaceQuotes().visit(original_tree)
    result = ast.dump(normalized_tree, include_attributes=True)
    return fastapi.Response(content=result, media_type="text/plain")
