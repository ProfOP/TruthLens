from pathlib import Path
import hashlib
import json
import re

import pandas as pd


# ============================================================
# CONFIGURATION
# ============================================================

TRAIN_INPUT = Path(
    r"D:\TruthLens\data\LIAR\train.tsv"
)

VALIDATION_INPUT = Path(
    r"D:\TruthLens\data\LIAR\valid.tsv"
)

TEST_INPUT = Path(
    r"D:\TruthLens\data\LIAR\test.tsv"
)

OUTPUT_DIR = Path(
    r"D:\TruthLens\data\processed\liar"
)

TRAIN_OUTPUT = OUTPUT_DIR / "train.csv"
VALIDATION_OUTPUT = OUTPUT_DIR / "validation.csv"
TEST_OUTPUT = OUTPUT_DIR / "test.csv"
REPORT_OUTPUT = OUTPUT_DIR / "dataset_report.json"


# ============================================================
# LIAR COLUMN NAMES
# ============================================================

COLUMNS = [
    "id",
    "label",
    "statement",
    "subjects",
    "speaker",
    "job_title",
    "state_info",
    "party_affiliation",
    "barely_true_counts",
    "false_counts",
    "half_true_counts",
    "mostly_true_counts",
    "pants_on_fire_counts",
    "context"
]


# ============================================================
# VALID LABELS
# ============================================================

VALID_LABELS = {
    "pants-fire",
    "false",
    "barely-true",
    "half-true",
    "mostly-true",
    "true"
}


# ============================================================
# OPTIONAL NUMERIC LABEL MAPPING
# ============================================================

LABEL_TO_ID = {
    "pants-fire": 0,
    "false": 1,
    "barely-true": 2,
    "half-true": 3,
    "mostly-true": 4,
    "true": 5
}


# ============================================================
# TEXT NORMALIZATION
# ============================================================

def normalize_text(value):
    """
    Conservative text cleaning.

    Missing metadata becomes an empty string.

    We do NOT aggressively preprocess the statement because
    transformer models should receive natural language.
    """

    if pd.isna(value):
        return ""

    value = str(value)

    value = value.replace("\x00", " ")

    value = re.sub(
        r"\s+",
        " ",
        value
    )

    return value.strip()


def normalize_for_duplicate_detection(value):
    """
    Stronger normalization used only for duplicate detection.
    """

    return normalize_text(value).lower()


# ============================================================
# FINGERPRINT
# ============================================================

def create_fingerprint(statement):
    """
    Create a stable fingerprint for a normalized claim.
    """

    normalized = normalize_for_duplicate_detection(
        statement
    )

    return hashlib.sha256(
        normalized.encode("utf-8")
    ).hexdigest()


# ============================================================
# LOAD LIAR FILE
# ============================================================

def load_liar_file(path, split_name):

    if not path.exists():

        raise FileNotFoundError(
            f"\n{split_name} file not found:\n{path}"
        )

    print(
        f"\nLoading {split_name}:"
    )

    print(path)

    df = pd.read_csv(
        path,
        sep="\t",
        header=None,
        names=COLUMNS,
        dtype=str
    )

    print(
        f"Rows: {len(df):,}"
    )

    return df


# ============================================================
# CLEAN SPLIT
# ============================================================

def clean_split(df, split_name):

    print(
        f"\nCleaning {split_name}..."
    )

    original_rows = len(df)

    # --------------------------------------------------------
    # Clean text-based columns
    # --------------------------------------------------------

    text_columns = [
        "id",
        "label",
        "statement",
        "subjects",
        "speaker",
        "job_title",
        "state_info",
        "party_affiliation",
        "context"
    ]

    for column in text_columns:

        df[column] = df[column].apply(
            normalize_text
        )

    # --------------------------------------------------------
    # Validate labels
    # --------------------------------------------------------

    unexpected_labels = (
        set(df["label"].unique())
        - VALID_LABELS
    )

    if unexpected_labels:

        raise ValueError(
            f"Unexpected labels in {split_name}: "
            f"{unexpected_labels}"
        )

    # --------------------------------------------------------
    # Remove rows with empty statements
    # --------------------------------------------------------

    empty_statement_mask = (
        df["statement"].eq("")
    )

    empty_statement_rows = int(
        empty_statement_mask.sum()
    )

    if empty_statement_rows > 0:

        print(
            f"Removing {empty_statement_rows:,} "
            "rows with empty statements."
        )

        df = df.loc[
            ~empty_statement_mask
        ].copy()

    # --------------------------------------------------------
    # Create statement fingerprint
    # --------------------------------------------------------

    df["fingerprint"] = (
        df["statement"]
        .apply(create_fingerprint)
    )

    # --------------------------------------------------------
    # Add numeric label
    # --------------------------------------------------------

    df["label_id"] = (
        df["label"]
        .map(LABEL_TO_ID)
    )

    # --------------------------------------------------------
    # Add split
    # --------------------------------------------------------

    df["split"] = split_name

    # --------------------------------------------------------
    # Statistics
    # --------------------------------------------------------

    removed_rows = (
        original_rows - len(df)
    )

    return (
        df,
        {
            "original_rows": original_rows,
            "empty_statement_rows_removed":
                empty_statement_rows,
            "rows_after_basic_cleaning":
                len(df),
            "rows_removed":
                removed_rows
        }
    )


# ============================================================
# PRINT LABEL DISTRIBUTION
# ============================================================

def print_distribution(name, df):

    print(
        f"\n{name.upper()} LABEL DISTRIBUTION"
    )

    print("-" * 50)

    counts = (
        df["label"]
        .value_counts()
        .reindex(
            LABEL_TO_ID.keys(),
            fill_value=0
        )
    )

    for label, count in counts.items():

        print(
            f"{label:15} {count:,}"
        )

    print(
        f"{'TOTAL':15} {len(df):,}"
    )


# ============================================================
# REMOVE DUPLICATES WITHIN TRAIN
# ============================================================

def clean_training_duplicates(train_df):

    print(
        "\nChecking duplicates inside training set..."
    )

    # --------------------------------------------------------
    # Find claims occurring with multiple labels
    # --------------------------------------------------------

    label_counts = (
        train_df
        .groupby("fingerprint")["label"]
        .nunique()
    )

    conflicting_fingerprints = set(
        label_counts[
            label_counts > 1
        ].index
    )

    conflicting_rows = int(
        train_df["fingerprint"]
        .isin(conflicting_fingerprints)
        .sum()
    )

    if conflicting_rows > 0:

        print(
            f"Found {conflicting_rows:,} training rows "
            "belonging to repeated statements with "
            "conflicting labels."
        )

        print(
            "Removing these ambiguous statements."
        )

        train_df = train_df[
            ~train_df["fingerprint"].isin(
                conflicting_fingerprints
            )
        ].copy()

    # --------------------------------------------------------
    # Remove remaining exact duplicate statements
    # --------------------------------------------------------

    before = len(train_df)

    train_df = (
        train_df
        .drop_duplicates(
            subset=["fingerprint"],
            keep="first"
        )
        .copy()
    )

    duplicate_rows_removed = (
        before - len(train_df)
    )

    print(
        f"Non-conflicting duplicate training rows removed: "
        f"{duplicate_rows_removed:,}"
    )

    return (
        train_df,
        conflicting_rows,
        duplicate_rows_removed
    )


# ============================================================
# REMOVE CROSS-SPLIT LEAKAGE
# ============================================================

def remove_cross_split_leakage(
    train_df,
    validation_df,
    test_df
):

    print(
        "\nChecking cross-split statement leakage..."
    )

    train_fingerprints = set(
        train_df["fingerprint"]
    )

    # --------------------------------------------------------
    # Validation vs Train
    # --------------------------------------------------------

    validation_leak_mask = (
        validation_df["fingerprint"]
        .isin(train_fingerprints)
    )

    validation_leakage = int(
        validation_leak_mask.sum()
    )

    if validation_leakage > 0:

        print(
            f"Removing {validation_leakage:,} "
            "validation rows also present in training."
        )

        validation_df = validation_df.loc[
            ~validation_leak_mask
        ].copy()

    # --------------------------------------------------------
    # Test vs Train
    # --------------------------------------------------------

    test_leak_mask = (
        test_df["fingerprint"]
        .isin(train_fingerprints)
    )

    test_train_leakage = int(
        test_leak_mask.sum()
    )

    if test_train_leakage > 0:

        print(
            f"Removing {test_train_leakage:,} "
            "test rows also present in training."
        )

        test_df = test_df.loc[
            ~test_leak_mask
        ].copy()

    # --------------------------------------------------------
    # Test vs Validation
    # --------------------------------------------------------

    validation_fingerprints = set(
        validation_df["fingerprint"]
    )

    test_validation_mask = (
        test_df["fingerprint"]
        .isin(validation_fingerprints)
    )

    test_validation_leakage = int(
        test_validation_mask.sum()
    )

    if test_validation_leakage > 0:

        print(
            f"Removing {test_validation_leakage:,} "
            "test rows also present in validation."
        )

        test_df = test_df.loc[
            ~test_validation_mask
        ].copy()

    return (
        train_df,
        validation_df,
        test_df,
        {
            "validation_vs_train":
                validation_leakage,

            "test_vs_train":
                test_train_leakage,

            "test_vs_validation":
                test_validation_leakage
        }
    )


# ============================================================
# VERIFY NO LEAKAGE
# ============================================================

def verify_no_leakage(
    train_df,
    validation_df,
    test_df
):

    train_fp = set(
        train_df["fingerprint"]
    )

    validation_fp = set(
        validation_df["fingerprint"]
    )

    test_fp = set(
        test_df["fingerprint"]
    )

    assert train_fp.isdisjoint(
        validation_fp
    ), (
        "Leakage remains between "
        "train and validation."
    )

    assert train_fp.isdisjoint(
        test_fp
    ), (
        "Leakage remains between "
        "train and test."
    )

    assert validation_fp.isdisjoint(
        test_fp
    ), (
        "Leakage remains between "
        "validation and test."
    )

    print(
        "\nNo statement leakage remains "
        "between dataset splits."
    )


# ============================================================
# LABEL COUNTS FOR JSON REPORT
# ============================================================

def get_label_counts(df):

    counts = (
        df["label"]
        .value_counts()
    )

    return {
        label: int(
            counts.get(label, 0)
        )
        for label in LABEL_TO_ID
    }


# ============================================================
# MAIN
# ============================================================

def main():

    print("=" * 60)
    print("TruthLens - LIAR Dataset Preparation")
    print("=" * 60)

    OUTPUT_DIR.mkdir(
        parents=True,
        exist_ok=True
    )

    # --------------------------------------------------------
    # 1. Load official splits
    # --------------------------------------------------------

    train_df = load_liar_file(
        TRAIN_INPUT,
        "train"
    )

    validation_df = load_liar_file(
        VALIDATION_INPUT,
        "validation"
    )

    test_df = load_liar_file(
        TEST_INPUT,
        "test"
    )

    # --------------------------------------------------------
    # 2. Clean each split
    # --------------------------------------------------------

    train_df, train_stats = clean_split(
        train_df,
        "train"
    )

    validation_df, validation_stats = clean_split(
        validation_df,
        "validation"
    )

    test_df, test_stats = clean_split(
        test_df,
        "test"
    )

    # --------------------------------------------------------
    # 3. Clean duplicate statements in training
    # --------------------------------------------------------

    (
        train_df,
        conflicting_train_rows,
        duplicate_train_rows
    ) = clean_training_duplicates(
        train_df
    )

    # --------------------------------------------------------
    # 4. Remove cross-split leakage
    # --------------------------------------------------------

    (
        train_df,
        validation_df,
        test_df,
        leakage_stats
    ) = remove_cross_split_leakage(
        train_df,
        validation_df,
        test_df
    )

    # --------------------------------------------------------
    # 5. Final leakage verification
    # --------------------------------------------------------

    verify_no_leakage(
        train_df,
        validation_df,
        test_df
    )

    # --------------------------------------------------------
    # 6. Print class distributions
    # --------------------------------------------------------

    print_distribution(
        "train",
        train_df
    )

    print_distribution(
        "validation",
        validation_df
    )

    print_distribution(
        "test",
        test_df
    )

    # --------------------------------------------------------
    # 7. Select output columns
    # --------------------------------------------------------

    output_columns = [
        "id",
        "statement",
        "label",
        "label_id",
        "subjects",
        "speaker",
        "job_title",
        "state_info",
        "party_affiliation",
        "barely_true_counts",
        "false_counts",
        "half_true_counts",
        "mostly_true_counts",
        "pants_on_fire_counts",
        "context",
        "split",
        "fingerprint"
    ]

    # --------------------------------------------------------
    # 8. Save processed files
    # --------------------------------------------------------

    print(
        "\nSaving processed LIAR datasets..."
    )

    train_df[
        output_columns
    ].to_csv(
        TRAIN_OUTPUT,
        index=False,
        encoding="utf-8"
    )

    validation_df[
        output_columns
    ].to_csv(
        VALIDATION_OUTPUT,
        index=False,
        encoding="utf-8"
    )

    test_df[
        output_columns
    ].to_csv(
        TEST_OUTPUT,
        index=False,
        encoding="utf-8"
    )

    # --------------------------------------------------------
    # 9. Dataset report
    # --------------------------------------------------------

    report = {

        "dataset": "LIAR",

        "task": (
            "Six-class truthfulness / "
            "claim classification"
        ),

        "label_mapping": LABEL_TO_ID,

        "official_split_preserved": True,

        "original_rows": {
            "train": train_stats["original_rows"],
            "validation":
                validation_stats["original_rows"],
            "test": test_stats["original_rows"]
        },

        "final_rows": {
            "train": len(train_df),
            "validation": len(validation_df),
            "test": len(test_df)
        },

        "training_cleanup": {
            "conflicting_duplicate_rows_removed":
                conflicting_train_rows,

            "duplicate_rows_removed":
                duplicate_train_rows
        },

        "cross_split_leakage_removed":
            leakage_stats,

        "class_distribution": {
            "train":
                get_label_counts(train_df),

            "validation":
                get_label_counts(validation_df),

            "test":
                get_label_counts(test_df)
        }
    }

    with open(
        REPORT_OUTPUT,
        "w",
        encoding="utf-8"
    ) as file:

        json.dump(
            report,
            file,
            indent=4,
            ensure_ascii=False
        )

    # --------------------------------------------------------
    # 10. Complete
    # --------------------------------------------------------

    print(
        "\n" + "=" * 60
    )

    print(
        "LIAR DATASET PREPARATION COMPLETE"
    )

    print(
        "=" * 60
    )

    print(
        "\nGenerated files:"
    )

    print(
        f"\nTraining:\n{TRAIN_OUTPUT}"
    )

    print(
        f"\nValidation:\n{VALIDATION_OUTPUT}"
    )

    print(
        f"\nTest:\n{TEST_OUTPUT}"
    )

    print(
        f"\nReport:\n{REPORT_OUTPUT}"
    )

    print(
        "\nOriginal LIAR train/validation/test "
        "assignments were preserved."
    )

    print(
        "Duplicate statement leakage was removed."
    )


# ============================================================
# RUN
# ============================================================

if __name__ == "__main__":
    main()