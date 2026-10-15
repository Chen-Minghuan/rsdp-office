"""Validate target documentation schemas/examples; does not call application APIs."""

import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


def main():
    folder = Path(__file__).resolve().parent
    schema = json.loads((folder / "spatial-api.schema.json").read_text())
    examples = json.loads((folder / "spatial-api.examples.json").read_text())["examples"]
    Draft202012Validator.check_schema(schema)
    positive = negative = 0
    failures = []
    for case in examples:
        definition = case["definition"]
        if definition not in schema["$defs"]:
            failures.append(f"{case['name']}: unknown definition {definition}")
            continue
        selected = dict(schema, **{"$ref": f"#/$defs/{definition}"})
        validator = Draft202012Validator(selected, format_checker=FormatChecker())
        errors = list(validator.iter_errors(case["payload"]))
        if bool(errors) == case["expectedSchemaValid"]:
            detail = errors[0].message if errors else "invalid case unexpectedly accepted"
            failures.append(f"{case['name']}: {detail}")
        if case["httpStatus"] is not None and case["payload"]["code"] != case["httpStatus"]:
            failures.append(f"{case['name']}: HTTP status/code mismatch")
        if case["expectedSchemaValid"]:
            positive += 1
        else:
            negative += 1
    if failures:
        raise SystemExit("\n".join(failures))
    print(f"PASS: schema self-check; {positive} valid and {negative} rejected examples.")
    print("Documentation structure only; no application/API/database/recognition tests run.")


if __name__ == "__main__":
    main()
