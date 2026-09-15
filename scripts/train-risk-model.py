"""Train risk-lr-v1: logistic regression fraud score for risk-service.

Synthetic training data, not real fraud history: this repo has no labelled
fraud cases to learn from, so the label is a hand-written rule (high velocity,
large z-score, or a first-time beneficiary moving an unusual amount) plus 5%
noise. It teaches the model the *shape* risk-service should score, nothing
more. v2 should replace this with real rows from `risk_decision.features` in
Postgres, labelled after the fact by analysts once chargebacks/disputes exist.

Run:  python scripts/train-risk-model.py
Installed for this: skl2onnx 1.20.0, onnx 1.22.0 (brought by skl2onnx).
Already present: scikit-learn 1.7.2, onnxruntime 1.25.0.

The output .onnx is committed to the repo (risk-service loads it at
startup) — re-run and re-commit only if the training data or model changes.
"""
import os
from datetime import datetime, timezone

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import train_test_split
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType
import onnxruntime as ort

MODEL_PATH = os.path.join(
    os.path.dirname(__file__), "..",
    "services", "risk-service", "src", "main", "resources", "model", "risk-lr-v1.onnx",
)
FEATURES = ["countLastMinute", "amountZScore", "newBeneficiary"]
N = 20_000
TARGET_OPSET = 18  # comfortably within onnxruntime 1.25's supported range


def make_dataset(rng):
    # countLastMinute: mostly 1-5, 5% of rows get a heavy tail up to 30.
    count = rng.poisson(lam=2, size=N) + 1
    tail = rng.random(N) < 0.05
    count = np.where(tail, rng.integers(10, 31, size=N), count).clip(1, 30)

    # amountZScore: N(0,1), 5% of rows get a fat tail in either direction.
    z = rng.normal(0, 1, size=N)
    tail = rng.random(N) < 0.05
    z[tail] += rng.exponential(scale=3, size=tail.sum()) * rng.choice([-1, 1], size=tail.sum())

    new_beneficiary = (rng.random(N) < 0.15).astype(np.float64)

    rule = (count >= 8) | (z >= 3) | ((new_beneficiary == 1) & (z >= 1.5))
    label = rule.astype(int)
    noisy = rng.random(N) < 0.05
    label[noisy] = 1 - label[noisy]

    X = np.column_stack([count, z, new_beneficiary]).astype(np.float32)
    return X, label


def main():
    rng = np.random.default_rng(42)
    X, y = make_dataset(rng)
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )

    clf = LogisticRegression().fit(X_train, y_train)
    auc = roc_auc_score(y_test, clf.predict_proba(X_test)[:, 1])
    print(f"held-out AUC: {auc:.4f}")
    print(f"coefficients {dict(zip(FEATURES, clf.coef_[0]))}")
    print(f"intercept {clf.intercept_[0]}")

    onnx_model = convert_sklearn(
        clf,
        initial_types=[("input", FloatTensorType([None, 3]))],
        options={id(clf): {"zipmap": False}},
        target_opset=TARGET_OPSET,
    )
    meta = {
        "version": "lr-v1",
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "auc": f"{auc:.4f}",
        "features": ",".join(FEATURES),
    }
    for key, value in meta.items():
        entry = onnx_model.metadata_props.add()
        entry.key, entry.value = key, value

    os.makedirs(os.path.dirname(MODEL_PATH), exist_ok=True)
    with open(MODEL_PATH, "wb") as f:
        f.write(onnx_model.SerializeToString())
    print(f"wrote {os.path.abspath(MODEL_PATH)} ({os.path.getsize(MODEL_PATH)} bytes, opset {TARGET_OPSET})")

    sanity_check(MODEL_PATH)


def sanity_check(path):
    sess = ort.InferenceSession(path)
    in_ = sess.get_inputs()[0]
    outs = sess.get_outputs()
    print(f"input: {in_.name} {in_.shape}")
    for o in outs:
        print(f"output: {o.name} {o.shape}")
    prob_idx = [o.name for o in outs].index("probabilities")

    def score(row):
        result = sess.run(None, {in_.name: np.array([row], dtype=np.float32)})
        return result[prob_idx][0][1]

    quiet = score([0, 0, 0])
    loud = score([12, 4, 1])
    print(f"score([0,0,0]) = {quiet:.4f}")
    print(f"score([12,4,1]) = {loud:.4f}")
    assert quiet < 0.2, f"expected quiet score < 0.2, got {quiet}"
    assert loud > 0.8, f"expected loud score > 0.8, got {loud}"


if __name__ == "__main__":
    main()
