from pathlib import Path
import hashlib
import json
import re

import pandas as pd
from sklearn.model_selection import train_test_split


# ============================================================
# CONFIGURATION
# ============================================================

RANDOM_SEED = 42

TRAIN_SIZE = 0.80
VALIDATION_SIZE = 0.10
TEST_SIZE = 0.10

# Raw WELFake dataset
INPUT_FILE = Path(
    r"D:\TruthLens\data\raw\WELFake\WELFake_Dataset.csv"
)

# Processed WELFake data will be stored here
OUTPUT_DIR = Path(
    r"D:\TruthLens\data\processed\welfake"
)

TRAIN_FILE = OUTPUT_DIR / "train.csv"
VALIDATION_FILE = OUTPUT_DIR / "validation.csv"
TEST_FILE = OUTPUT_DIR / "test.csv"
CLEANED_FILE = OUTPUT_DIR / "welfake_cleaned.csv"
REPORT_FILE = OUTPUT_DIR / "dataset_report.json"


# ============================================================
# TEXT CLEANING
# ============================================================

def normalize_text(value):
    """
    Perform conservative text cleaning.

    We intentionally do NOT:
    - remove punctuation
    - remove stop words
    - stem words
    - lowercase the model input

    DistilBERT's tokenizer will handle tokenization later.
    """

    if pd.isna(value):
        return ""

    value = str(value)

    # Remove null characters
    value = value.replace("\x00", " ")

    # Replace repeated spaces, tabs and newlines with one space
    value = re.sub(r"\s+", " ", value)

    return value.strip()


def normalize_for_duplicate_detection(value):
    """
    Stronger normalization used ONLY for duplicate detection.

    The actual text given to DistilBERT is not lowercased here.
    """

    value = normalize_text(value)

    return value.lower()


# ============================================================
# DUPLICATE FINGERPRINT
# ============================================================

def create_fingerprint(title, text):
    """
    Create a stable SHA-256 fingerprint from normalized
    title + article text.

    This lets us detect duplicate articles.
    """

    normalized_title = normalize_for_duplicate_detection(title)
    normalized_text = normalize_for_duplicate_detection(text)

    combined = normalized_title + "||" + normalized_text

    return hashlib.sha256(
        combined.encode("utf-8")
    ).hexdigest()


# ============================================================
# MODEL INPUT
# ============================================================

def create_model_input(title, text):
    """
    Create the text that will eventually be passed to
    the DistilBERT tokenizer.

    Special tokens such as [CLS] and [SEP] are NOT manually
    inserted here. The tokenizer will handle those later.
    """

    title = normalize_text(title)
    text = normalize_text(text)

    if title and text:
        return f"Title: {title}\nArticle: {text}"

    if title:
        return f"Title: {title}"

    return f"Article: {text}"


# ============================================================
# HELPER FUNCTIONS
# ============================================================

def label_counts(df):
    """
    Return class distribution statistics.
    """

    counts = df["label"].value_counts().sort_index()

    return {
        "real_0": int(counts.get(0, 0)),
        "fake_1": int(counts.get(1, 0)),
        "total": int(len(df)),
    }


def print_distribution(name, df):
    """
    Print the Real/Fake distribution of a dataframe.
    """

    print(f"\n{name}")
    print("-" * 50)
    print(f"Total: {len(df):,}")
    print(f"Real (0): {(df['label'] == 0).sum():,}")
    print(f"Fake (1): {(df['label'] == 1).sum():,}")


# ============================================================
# MAIN PREPROCESSING
# ============================================================

def main():

    print("=" * 60)
    print("TruthLens - WELFake Dataset Preparation")
    print("=" * 60)

    # --------------------------------------------------------
    # 1. Verify input file
    # --------------------------------------------------------

    if not INPUT_FILE.exists():
        raise FileNotFoundError(
            f"\nDataset not found:\n{INPUT_FILE}\n\n"
            "Check that WELFake_Dataset.csv exists at this location."
        )

    # Create output directory automatically if it does not exist
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    # --------------------------------------------------------
    # 2. Load dataset
    # --------------------------------------------------------

    print(f"\nLoading dataset from:")
    print(INPUT_FILE)

    df = pd.read_csv(
        INPUT_FILE,
        low_memory=False
    )

    original_rows = len(df)

    print(f"\nRaw rows: {original_rows:,}")
    print(f"Columns: {list(df.columns)}")

    # --------------------------------------------------------
    # 3. Validate required columns
    # --------------------------------------------------------

    required_columns = {
        "title",
        "text",
        "label"
    }

    missing_columns = required_columns - set(df.columns)

    if missing_columns:
        raise ValueError(
            "Dataset is missing required columns: "
            f"{sorted(missing_columns)}"
        )

    # --------------------------------------------------------
    # 4. Keep only useful columns
    # --------------------------------------------------------

    # WELFake contains an "Unnamed: 0" index column.
    # We do not need it for training.

    df = df[
        [
            "title",
            "text",
            "label"
        ]
    ].copy()

    # --------------------------------------------------------
    # 5. Validate labels
    # --------------------------------------------------------

    df["label"] = pd.to_numeric(
        df["label"],
        errors="coerce"
    )

    invalid_label_rows = int(
        df["label"].isna().sum()
    )

    if invalid_label_rows > 0:

        print(
            f"\nRemoving {invalid_label_rows:,} rows "
            "with invalid or missing labels."
        )

        df = df.dropna(
            subset=["label"]
        ).copy()

    df["label"] = df["label"].astype(int)

    unexpected_labels = (
        set(df["label"].unique()) - {0, 1}
    )

    if unexpected_labels:

        raise ValueError(
            f"Unexpected labels found: {unexpected_labels}. "
            "Expected only 0 (Real) and 1 (Fake)."
        )

    # --------------------------------------------------------
    # 6. Clean title and article text
    # --------------------------------------------------------

    print("\nCleaning title and article text...")

    df["title"] = df["title"].apply(
        normalize_text
    )

    df["text"] = df["text"].apply(
        normalize_text
    )

    # --------------------------------------------------------
    # 7. Remove rows with no usable content
    # --------------------------------------------------------

    empty_content_mask = (
        df["title"].eq("")
        &
        df["text"].eq("")
    )

    empty_content_rows = int(
        empty_content_mask.sum()
    )

    if empty_content_rows > 0:

        print(
            f"Removing {empty_content_rows:,} rows "
            "with no title AND no article text."
        )

        df = df.loc[
            ~empty_content_mask
        ].copy()

    # --------------------------------------------------------
    # 8. Generate fingerprints
    # --------------------------------------------------------

    print("\nChecking duplicate articles...")

    df["fingerprint"] = [
        create_fingerprint(title, text)
        for title, text
        in zip(df["title"], df["text"])
    ]

    # --------------------------------------------------------
    # 9. Detect duplicates with conflicting labels
    # --------------------------------------------------------

    label_counts_per_fingerprint = (
        df.groupby("fingerprint")["label"]
        .nunique()
    )

    conflicting_fingerprints = set(
        label_counts_per_fingerprint[
            label_counts_per_fingerprint > 1
        ].index
    )

    conflicting_rows = int(
        df["fingerprint"]
        .isin(conflicting_fingerprints)
        .sum()
    )

    if conflicting_rows > 0:

        print(
            f"\nWARNING: Found {conflicting_rows:,} rows "
            "belonging to duplicate articles with "
            "conflicting labels."
        )

        print(
            "Removing these records because their labels "
            "cannot be trusted."
        )

        df = df[
            ~df["fingerprint"].isin(
                conflicting_fingerprints
            )
        ].copy()

    else:

        print(
            "No conflicting duplicate labels found."
        )

    # --------------------------------------------------------
    # 10. Remove duplicate articles
    # --------------------------------------------------------

    rows_before_deduplication = len(df)

    df = df.drop_duplicates(
        subset=["fingerprint"],
        keep="first"
    ).copy()

    duplicate_rows_removed = (
        rows_before_deduplication - len(df)
    )

    print(
        f"Duplicate rows removed: "
        f"{duplicate_rows_removed:,}"
    )

    # --------------------------------------------------------
    # 11. Create DistilBERT model input
    # --------------------------------------------------------

    print("\nCreating model input text...")

    df["model_input"] = [
        create_model_input(title, text)
        for title, text
        in zip(df["title"], df["text"])
    ]

    # --------------------------------------------------------
    # 12. Add stable example IDs
    # --------------------------------------------------------

    df = df.reset_index(drop=True)

    df.insert(
        0,
        "example_id",
        [
            f"WELFAKE_{i:06d}"
            for i in range(
                1,
                len(df) + 1
            )
        ]
    )

    # --------------------------------------------------------
    # 13. Dataset statistics
    # --------------------------------------------------------

    cleaned_rows = len(df)

    print_distribution(
        "CLEANED DATASET",
        df
    )

    # --------------------------------------------------------
    # 14. Create Train / Validation / Test split
    # --------------------------------------------------------

    print(
        "\nCreating stratified "
        "train/validation/test split..."
    )

    # First:
    #
    # 80% -> training
    # 20% -> temporary

    train_df, temp_df = train_test_split(
        df,
        test_size=(
            VALIDATION_SIZE + TEST_SIZE
        ),
        random_state=RANDOM_SEED,
        stratify=df["label"]
    )

    # Divide the remaining 20% equally:
    #
    # 10% validation
    # 10% test

    relative_test_size = (
        TEST_SIZE
        /
        (VALIDATION_SIZE + TEST_SIZE)
    )

    validation_df, test_df = train_test_split(
        temp_df,
        test_size=relative_test_size,
        random_state=RANDOM_SEED,
        stratify=temp_df["label"]
    )

    # --------------------------------------------------------
    # 15. Leakage safety checks
    # --------------------------------------------------------

    print("\nChecking for data leakage...")

    train_fingerprints = set(
        train_df["fingerprint"]
    )

    validation_fingerprints = set(
        validation_df["fingerprint"]
    )

    test_fingerprints = set(
        test_df["fingerprint"]
    )

    assert train_fingerprints.isdisjoint(
        validation_fingerprints
    ), (
        "Leakage detected between "
        "train and validation."
    )

    assert train_fingerprints.isdisjoint(
        test_fingerprints
    ), (
        "Leakage detected between "
        "train and test."
    )

    assert validation_fingerprints.isdisjoint(
        test_fingerprints
    ), (
        "Leakage detected between "
        "validation and test."
    )

    assert (
        len(train_df)
        + len(validation_df)
        + len(test_df)
        == cleaned_rows
    ), "Split sizes do not add up correctly."

    print("No duplicate leakage detected.")

    # --------------------------------------------------------
    # 16. Print split statistics
    # --------------------------------------------------------

    print_distribution(
        "TRAIN",
        train_df
    )

    print_distribution(
        "VALIDATION",
        validation_df
    )

    print_distribution(
        "TEST",
        test_df
    )

    # --------------------------------------------------------
    # 17. Save datasets
    # --------------------------------------------------------

    print("\nSaving processed datasets...")

    output_columns = [
        "example_id",
        "title",
        "text",
        "model_input",
        "label",
        "fingerprint"
    ]

    df[
        output_columns
    ].to_csv(
        CLEANED_FILE,
        index=False,
        encoding="utf-8"
    )

    train_df[
        output_columns
    ].to_csv(
        TRAIN_FILE,
        index=False,
        encoding="utf-8"
    )

    validation_df[
        output_columns
    ].to_csv(
        VALIDATION_FILE,
        index=False,
        encoding="utf-8"
    )

    test_df[
        output_columns
    ].to_csv(
        TEST_FILE,
        index=False,
        encoding="utf-8"
    )

    # --------------------------------------------------------
    # 18. Create dataset report
    # --------------------------------------------------------

    report = {

        "dataset": "WELFake",

        "label_mapping": {
            "0": "REAL",
            "1": "FAKE"
        },

        "random_seed": RANDOM_SEED,

        "raw_rows": original_rows,

        "cleaned_rows": cleaned_rows,

        "removed": {

            "invalid_label_rows":
                invalid_label_rows,

            "empty_content_rows":
                empty_content_rows,

            "conflicting_duplicate_rows":
                conflicting_rows,

            "duplicate_rows":
                duplicate_rows_removed
        },

        "split_ratio": {
            "train": TRAIN_SIZE,
            "validation": VALIDATION_SIZE,
            "test": TEST_SIZE
        },

        "class_distribution": {

            "full":
                label_counts(df),

            "train":
                label_counts(train_df),

            "validation":
                label_counts(validation_df),

            "test":
                label_counts(test_df)
        }
    }

    with open(
        REPORT_FILE,
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
    # 19. Complete
    # --------------------------------------------------------

    print("\n" + "=" * 60)
    print("DATASET PREPARATION COMPLETE")
    print("=" * 60)

    print("\nGenerated files:")

    print(
        f"\nCleaned dataset:\n{CLEANED_FILE}"
    )

    print(
        f"\nTraining dataset:\n{TRAIN_FILE}"
    )

    print(
        f"\nValidation dataset:\n{VALIDATION_FILE}"
    )

    print(
        f"\nTest dataset:\n{TEST_FILE}"
    )

    print(
        f"\nDataset report:\n{REPORT_FILE}"
    )

    print(
        "\nNo duplicate fingerprints "
        "occur across splits."
    )

    print(
        f"Random seed: {RANDOM_SEED}"
    )


# ============================================================
# RUN SCRIPT
# ============================================================

if __name__ == "__main__":
    main()