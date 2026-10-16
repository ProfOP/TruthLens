from pathlib import Path
import json
import re

import pandas as pd


# ============================================================
# CONFIGURATION
# ============================================================

DATASET_ROOT = Path(
    r"D:\TruthLens\data\FaceForensics"
)

FRAMES_DIR = (
    DATASET_ROOT / "FF++C32-Frames"
)

METADATA_FILE = (
    DATASET_ROOT / "FF++C32-All-frames.csv"
)

SPLITS_DIR = (
    DATASET_ROOT / "splits"
)

TRAIN_SPLIT_FILE = (
    SPLITS_DIR / "train.json"
)

VALIDATION_SPLIT_FILE = (
    SPLITS_DIR / "val.json"
)

TEST_SPLIT_FILE = (
    SPLITS_DIR / "test.json"
)

OUTPUT_DIR = Path(
    r"D:\TruthLens\data\processed\faceforensics"
)

FULL_MANIFEST_FILE = (
    OUTPUT_DIR / "full_manifest.csv"
)

TRAIN_OUTPUT = (
    OUTPUT_DIR / "train.csv"
)

VALIDATION_OUTPUT = (
    OUTPUT_DIR / "validation.csv"
)

TEST_OUTPUT = (
    OUTPUT_DIR / "test.csv"
)

REPORT_OUTPUT = (
    OUTPUT_DIR / "dataset_report.json"
)


# ============================================================
# EXPECTED CLASSES
# ============================================================

EXPECTED_CLASSES = [
    "Original",
    "Deepfakes",
    "Face2Face",
    "FaceShifter",
    "FaceSwap",
    "NeuralTextures"
]


# Binary labels used by TruthLens:
#
# 0 = Authentic
# 1 = Manipulated

BINARY_LABELS = {
    "Original": 0,
    "Deepfakes": 1,
    "Face2Face": 1,
    "FaceShifter": 1,
    "FaceSwap": 1,
    "NeuralTextures": 1
}


# ============================================================
# LOAD OFFICIAL SPLITS
# ============================================================

def load_split_file(path, split_name):
    """
    Load one of the official FaceForensics++ split JSON files.

    Each file normally contains pairs such as:

        ["071", "054"]

    The individual sequence IDs in those pairs determine which
    original sequences belong to train/validation/test.
    """

    if not path.exists():
        raise FileNotFoundError(
            f"\n{split_name} split file not found:\n"
            f"{path}"
        )

    with open(
        path,
        "r",
        encoding="utf-8"
    ) as file:
        data = json.load(file)

    sequence_ids = set()

    for item in data:

        # Standard FF++ format:
        # ["071", "054"]

        if isinstance(item, list):

            for sequence_id in item:

                sequence_ids.add(
                    str(sequence_id).zfill(3)
                )

        # Defensive support in case a split contains
        # individual sequence IDs.

        else:

            sequence_ids.add(
                str(item).zfill(3)
            )

    print(
        f"{split_name}: "
        f"{len(sequence_ids):,} unique sequence IDs"
    )

    return sequence_ids


# ============================================================
# VALIDATE SPLITS
# ============================================================

def validate_splits(
    train_ids,
    validation_ids,
    test_ids
):
    """
    Make sure the same source sequence cannot appear in
    more than one partition.
    """

    if not train_ids.isdisjoint(validation_ids):

        overlap = (
            train_ids & validation_ids
        )

        raise ValueError(
            "Sequence leakage between train and validation: "
            f"{sorted(overlap)[:10]}"
        )

    if not train_ids.isdisjoint(test_ids):

        overlap = (
            train_ids & test_ids
        )

        raise ValueError(
            "Sequence leakage between train and test: "
            f"{sorted(overlap)[:10]}"
        )

    if not validation_ids.isdisjoint(test_ids):

        overlap = (
            validation_ids & test_ids
        )

        raise ValueError(
            "Sequence leakage between validation and test: "
            f"{sorted(overlap)[:10]}"
        )

    all_ids = (
        train_ids
        | validation_ids
        | test_ids
    )

    print(
        f"\nTotal unique sequences across splits: "
        f"{len(all_ids):,}"
    )

    print(
        "No sequence IDs overlap between splits."
    )

    return all_ids


# ============================================================
# PARSE FILENAMES
# ============================================================

def parse_original_filename(filename):
    """
    Example:

        000_f0.jpg

    Returns:

        sequence_id = 000
        frame_number = 0
    """

    pattern = (
        r"^(\d{3})_f(\d+)\.(jpg|jpeg|png)$"
    )

    match = re.match(
        pattern,
        filename,
        flags=re.IGNORECASE
    )

    if match is None:
        return None

    sequence_id = match.group(1)
    frame_number = int(match.group(2))

    return {
        "target_sequence": sequence_id,
        "source_sequence": "",
        "sequence_key": sequence_id,
        "frame_number": frame_number
    }


def parse_manipulated_filename(filename):
    """
    Example:

        000_003_f2.jpg

    FaceForensics++ manipulated sequence naming:

        <target>_<source>

    Returns:

        target_sequence = 000
        source_sequence = 003
        sequence_key = 000_003
        frame_number = 2
    """

    pattern = (
        r"^(\d{3})_(\d{3})_f(\d+)"
        r"\.(jpg|jpeg|png)$"
    )

    match = re.match(
        pattern,
        filename,
        flags=re.IGNORECASE
    )

    if match is None:
        return None

    target_sequence = match.group(1)
    source_sequence = match.group(2)
    frame_number = int(match.group(3))

    return {
        "target_sequence": target_sequence,
        "source_sequence": source_sequence,
        "sequence_key":
            f"{target_sequence}_{source_sequence}",
        "frame_number": frame_number
    }


# ============================================================
# DETERMINE SPLIT
# ============================================================

def get_split_for_sequences(
    target_sequence,
    source_sequence,
    train_ids,
    validation_ids,
    test_ids
):
    """
    Determine which partition an image belongs to.

    Original image:
        only target_sequence is used.

    Manipulated image:
        target AND source must belong to the same partition.

    If they do not, the image is rejected rather than allowing
    identity/sequence leakage.
    """

    if source_sequence == "":

        if target_sequence in train_ids:
            return "train"

        if target_sequence in validation_ids:
            return "validation"

        if target_sequence in test_ids:
            return "test"

        return None

    # Manipulated frame

    if (
        target_sequence in train_ids
        and source_sequence in train_ids
    ):
        return "train"

    if (
        target_sequence in validation_ids
        and source_sequence in validation_ids
    ):
        return "validation"

    if (
        target_sequence in test_ids
        and source_sequence in test_ids
    ):
        return "test"

    return None


# ============================================================
# BUILD MANIFEST
# ============================================================

def build_manifest(
    train_ids,
    validation_ids,
    test_ids
):

    print(
        "\nScanning local image folders..."
    )

    records = []

    invalid_filename_count = 0
    unassigned_count = 0

    class_counts = {}

    for class_name in EXPECTED_CLASSES:

        class_dir = (
            FRAMES_DIR / class_name
        )

        if not class_dir.exists():

            raise FileNotFoundError(
                f"\nExpected class folder not found:\n"
                f"{class_dir}"
            )

        image_files = sorted(
            [
                file
                for file in class_dir.iterdir()
                if (
                    file.is_file()
                    and file.suffix.lower()
                    in {".jpg", ".jpeg", ".png"}
                )
            ]
        )

        class_counts[class_name] = len(
            image_files
        )

        print(
            f"\n{class_name}: "
            f"{len(image_files):,} images"
        )

        for image_path in image_files:

            filename = image_path.name

            if class_name == "Original":

                parsed = (
                    parse_original_filename(
                        filename
                    )
                )

            else:

                parsed = (
                    parse_manipulated_filename(
                        filename
                    )
                )

            if parsed is None:

                invalid_filename_count += 1

                print(
                    f"WARNING: Could not parse filename: "
                    f"{filename}"
                )

                continue

            split = get_split_for_sequences(
                parsed["target_sequence"],
                parsed["source_sequence"],
                train_ids,
                validation_ids,
                test_ids
            )

            if split is None:

                unassigned_count += 1

                continue

            binary_label = (
                BINARY_LABELS[class_name]
            )

            binary_class = (
                "Authentic"
                if binary_label == 0
                else "Manipulated"
            )

            records.append(
                {
                    "filename":
                        filename,

                    "image_path":
                        str(
                            image_path.resolve()
                        ),

                    "manipulation_type":
                        class_name,

                    "binary_label":
                        binary_label,

                    "binary_class":
                        binary_class,

                    "target_sequence":
                        parsed[
                            "target_sequence"
                        ],

                    "source_sequence":
                        parsed[
                            "source_sequence"
                        ],

                    "sequence_key":
                        parsed[
                            "sequence_key"
                        ],

                    "frame_number":
                        parsed[
                            "frame_number"
                        ],

                    "split":
                        split
                }
            )

    manifest = pd.DataFrame(
        records
    )

    return (
        manifest,
        class_counts,
        invalid_filename_count,
        unassigned_count
    )


# ============================================================
# VALIDATE LOCAL FILES
# ============================================================

def validate_image_paths(manifest):
    """
    Verify every path stored in the manifest exists.
    """

    print(
        "\nVerifying local image paths..."
    )

    missing_paths = []

    for path_string in manifest["image_path"]:

        path = Path(path_string)

        if not path.exists():

            missing_paths.append(
                path_string
            )

    if missing_paths:

        print(
            f"Missing image files: "
            f"{len(missing_paths):,}"
        )

        for path in missing_paths[:10]:
            print(path)

        raise FileNotFoundError(
            "Manifest contains missing image files."
        )

    print(
        "All manifest image paths exist."
    )


# ============================================================
# VERIFY SEQUENCE LEAKAGE
# ============================================================

def verify_sequence_leakage(manifest):
    """
    Verify that no original source identity/sequence occurs
    in more than one partition.

    Both target and source sequence IDs are considered.
    """

    print(
        "\nChecking sequence leakage..."
    )

    split_sequences = {}

    for split in [
        "train",
        "validation",
        "test"
    ]:

        split_df = manifest[
            manifest["split"] == split
        ]

        sequences = set(
            split_df[
                "target_sequence"
            ].tolist()
        )

        source_sequences = set(
            split_df[
                "source_sequence"
            ].tolist()
        )

        source_sequences.discard("")

        sequences.update(
            source_sequences
        )

        split_sequences[split] = sequences

    assert (
        split_sequences["train"]
        .isdisjoint(
            split_sequences["validation"]
        )
    ), (
        "Sequence leakage between "
        "train and validation."
    )

    assert (
        split_sequences["train"]
        .isdisjoint(
            split_sequences["test"]
        )
    ), (
        "Sequence leakage between "
        "train and test."
    )

    assert (
        split_sequences["validation"]
        .isdisjoint(
            split_sequences["test"]
        )
    ), (
        "Sequence leakage between "
        "validation and test."
    )

    print(
        "No sequence leakage detected."
    )


# ============================================================
# PRINT STATISTICS
# ============================================================

def print_split_statistics(
    split_name,
    df
):

    print(
        f"\n{split_name.upper()}"
    )

    print("-" * 60)

    print(
        f"Total images: "
        f"{len(df):,}"
    )

    authentic = int(
        (df["binary_label"] == 0).sum()
    )

    manipulated = int(
        (df["binary_label"] == 1).sum()
    )

    print(
        f"Authentic: {authentic:,}"
    )

    print(
        f"Manipulated: {manipulated:,}"
    )

    print(
        "\nManipulation types:"
    )

    counts = (
        df["manipulation_type"]
        .value_counts()
    )

    for class_name in EXPECTED_CLASSES:

        print(
            f"  {class_name:15} "
            f"{int(counts.get(class_name, 0)):,}"
        )


# ============================================================
# CREATE REPORT COUNTS
# ============================================================

def get_split_report(df):

    type_counts = (
        df["manipulation_type"]
        .value_counts()
    )

    return {
        "total_images":
            int(len(df)),

        "authentic":
            int(
                (df["binary_label"] == 0)
                .sum()
            ),

        "manipulated":
            int(
                (df["binary_label"] == 1)
                .sum()
            ),

        "manipulation_types": {
            class_name:
                int(
                    type_counts.get(
                        class_name,
                        0
                    )
                )
            for class_name
            in EXPECTED_CLASSES
        }
    }


# ============================================================
# MAIN
# ============================================================

def main():

    print("=" * 70)
    print(
        "TruthLens - FaceForensics++ "
        "Dataset Preparation"
    )
    print("=" * 70)

    # --------------------------------------------------------
    # 1. Verify important paths
    # --------------------------------------------------------

    if not FRAMES_DIR.exists():

        raise FileNotFoundError(
            f"\nFrames directory not found:\n"
            f"{FRAMES_DIR}"
        )

    if not METADATA_FILE.exists():

        raise FileNotFoundError(
            f"\nMetadata CSV not found:\n"
            f"{METADATA_FILE}"
        )

    OUTPUT_DIR.mkdir(
        parents=True,
        exist_ok=True
    )

    # --------------------------------------------------------
    # 2. Load official splits
    # --------------------------------------------------------

    print(
        "\nLoading official FaceForensics++ splits..."
    )

    train_ids = load_split_file(
        TRAIN_SPLIT_FILE,
        "Train"
    )

    validation_ids = load_split_file(
        VALIDATION_SPLIT_FILE,
        "Validation"
    )

    test_ids = load_split_file(
        TEST_SPLIT_FILE,
        "Test"
    )

    # --------------------------------------------------------
    # 3. Validate split sequence IDs
    # --------------------------------------------------------

    all_ids = validate_splits(
        train_ids,
        validation_ids,
        test_ids
    )

    # --------------------------------------------------------
    # 4. Build image manifest
    # --------------------------------------------------------

    (
        manifest,
        raw_class_counts,
        invalid_filename_count,
        unassigned_count
    ) = build_manifest(
        train_ids,
        validation_ids,
        test_ids
    )

    if manifest.empty:

        raise ValueError(
            "No usable images were found."
        )

    # --------------------------------------------------------
    # 5. Validate image paths
    # --------------------------------------------------------

    validate_image_paths(
        manifest
    )

    # --------------------------------------------------------
    # 6. Verify no sequence leakage
    # --------------------------------------------------------

    verify_sequence_leakage(
        manifest
    )

    # --------------------------------------------------------
    # 7. Separate splits
    # --------------------------------------------------------

    train_df = (
        manifest[
            manifest["split"] == "train"
        ]
        .copy()
        .reset_index(drop=True)
    )

    validation_df = (
        manifest[
            manifest["split"] == "validation"
        ]
        .copy()
        .reset_index(drop=True)
    )

    test_df = (
        manifest[
            manifest["split"] == "test"
        ]
        .copy()
        .reset_index(drop=True)
    )

    # --------------------------------------------------------
    # 8. Print statistics
    # --------------------------------------------------------

    print_split_statistics(
        "Train",
        train_df
    )

    print_split_statistics(
        "Validation",
        validation_df
    )

    print_split_statistics(
        "Test",
        test_df
    )

    # --------------------------------------------------------
    # 9. Save manifests
    # --------------------------------------------------------

    print(
        "\nSaving dataset manifests..."
    )

    manifest.to_csv(
        FULL_MANIFEST_FILE,
        index=False,
        encoding="utf-8"
    )

    train_df.to_csv(
        TRAIN_OUTPUT,
        index=False,
        encoding="utf-8"
    )

    validation_df.to_csv(
        VALIDATION_OUTPUT,
        index=False,
        encoding="utf-8"
    )

    test_df.to_csv(
        TEST_OUTPUT,
        index=False,
        encoding="utf-8"
    )

    # --------------------------------------------------------
    # 10. Generate report
    # --------------------------------------------------------

    report = {

        "dataset":
            "FaceForensics++ extracted C32 frames",

        "task":
            "Binary image manipulation detection",

        "binary_label_mapping": {
            "0": "Authentic",
            "1": "Manipulated"
        },

        "manipulation_types": [
            "Deepfakes",
            "Face2Face",
            "FaceShifter",
            "FaceSwap",
            "NeuralTextures"
        ],

        "split_method":
            "Official FaceForensics++ "
            "sequence-level split",

        "official_sequence_counts": {
            "train":
                len(train_ids),

            "validation":
                len(validation_ids),

            "test":
                len(test_ids),

            "total":
                len(all_ids)
        },

        "raw_images_by_type":
            raw_class_counts,

        "invalid_filenames":
            invalid_filename_count,

        "unassigned_images":
            unassigned_count,

        "usable_images":
            int(len(manifest)),

        "splits": {
            "train":
                get_split_report(
                    train_df
                ),

            "validation":
                get_split_report(
                    validation_df
                ),

            "test":
                get_split_report(
                    test_df
                )
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
    # 11. Final checks
    # --------------------------------------------------------

    assert (
        len(train_df)
        + len(validation_df)
        + len(test_df)
        == len(manifest)
    ), (
        "Split image counts do not match "
        "manifest size."
    )

    # --------------------------------------------------------
    # 12. Complete
    # --------------------------------------------------------

    print(
        "\n" + "=" * 70
    )

    print(
        "FACEFORENSICS++ DATASET "
        "PREPARATION COMPLETE"
    )

    print(
        "=" * 70
    )

    print(
        f"\nUsable images: "
        f"{len(manifest):,}"
    )

    print(
        f"Unassigned images: "
        f"{unassigned_count:,}"
    )

    print(
        f"Invalid filenames: "
        f"{invalid_filename_count:,}"
    )

    print(
        "\nGenerated files:"
    )

    print(
        f"\nFull manifest:\n"
        f"{FULL_MANIFEST_FILE}"
    )

    print(
        f"\nTraining manifest:\n"
        f"{TRAIN_OUTPUT}"
    )

    print(
        f"\nValidation manifest:\n"
        f"{VALIDATION_OUTPUT}"
    )

    print(
        f"\nTest manifest:\n"
        f"{TEST_OUTPUT}"
    )

    print(
        f"\nDataset report:\n"
        f"{REPORT_OUTPUT}"
    )

    print(
        "\nImages were NOT copied or moved."
    )

    print(
        "The generated CSV files point to "
        "the existing JPG files."
    )

    print(
        "\nIMPORTANT:"
    )

    print(
        "The binary dataset is class-imbalanced "
        "because all five manipulation methods "
        "are retained."
    )

    print(
        "We will handle this during model training "
        "rather than deleting useful data."
    )


# ============================================================
# RUN
# ============================================================

if __name__ == "__main__":
    main()