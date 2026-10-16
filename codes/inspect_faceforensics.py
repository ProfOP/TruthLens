from pathlib import Path
import pandas as pd


CSV_FILE = Path(
    r"D:\TruthLens\data\FaceForensics\FF++C32-All-frames.csv"
)

CSV_DIR = Path(
    r"D:\TruthLens\data\FaceForensics\CSVs"
)

FRAMES_DIR = Path(
    r"D:\TruthLens\data\FaceForensics\FF++C32-Frames"
)


print("=" * 70)
print("TruthLens - FaceForensics Dataset Inspection")
print("=" * 70)


# ============================================================
# 1. MAIN CSV
# ============================================================

print("\nMAIN CSV")
print("-" * 70)

if not CSV_FILE.exists():
    raise FileNotFoundError(
        f"Main CSV not found:\n{CSV_FILE}"
    )

df = pd.read_csv(
    CSV_FILE,
    low_memory=False
)

print(f"Rows: {len(df):,}")
print(f"Columns ({len(df.columns)}):")

for column in df.columns:
    print(f"  - {column}")


print("\nFirst 5 rows:")
print(df.head().to_string())


print("\nMissing values:")
print(df.isna().sum().to_string())


# ============================================================
# 2. FILES INSIDE CSVs/
# ============================================================

print("\n\nFILES INSIDE CSVs")
print("-" * 70)

if CSV_DIR.exists():

    csv_files = sorted(
        CSV_DIR.glob("*.csv")
    )

    if not csv_files:
        print("No CSV files found.")

    for file in csv_files:

        print(f"\n{file.name}")

        try:

            temp_df = pd.read_csv(
                file,
                low_memory=False
            )

            print(
                f"  Rows: {len(temp_df):,}"
            )

            print(
                f"  Columns: {list(temp_df.columns)}"
            )

            print("  First row:")

            if len(temp_df) > 0:
                print(
                    temp_df.head(1).to_string(
                        index=False
                    )
                )

        except Exception as error:

            print(
                f"  Could not read: {error}"
            )

else:

    print(
        f"CSV directory not found:\n{CSV_DIR}"
    )


# ============================================================
# 3. IMAGE FOLDERS
# ============================================================

print("\n\nIMAGE FOLDERS")
print("-" * 70)

expected_folders = [
    "Original",
    "Deepfakes",
    "Face2Face",
    "FaceShifter",
    "FaceSwap",
    "NeuralTextures"
]


for folder_name in expected_folders:

    folder = FRAMES_DIR / folder_name

    if not folder.exists():

        print(
            f"\n{folder_name}: NOT FOUND"
        )

        continue

    image_files = [
        file
        for file in folder.rglob("*")
        if file.suffix.lower()
        in {".jpg", ".jpeg", ".png"}
    ]

    print(
        f"\n{folder_name}: "
        f"{len(image_files):,} images"
    )

    print("Example filenames:")

    for file in image_files[:5]:

        print(
            f"  {file.name}"
        )


print("\n" + "=" * 70)
print("INSPECTION COMPLETE")
print("=" * 70)