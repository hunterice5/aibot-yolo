# ======================================================================
# 🎯 YOLOv8 Extreme Optimization Script for Kaggle/Colab (VALORANT V3)
# ----------------------------------------------------------------------
# 📦 สถานะ: พร้อมรันทันที (Plug-and-Play)
# ======================================================================

# 1. ติดตั้ง Library
print("⏳ Installing libraries...")
!pip install ultralytics roboflow onnx onnxruntime onnxslim -q

import os
from ultralytics import YOLO
from roboflow import Roboflow

# 2. โหลด Dataset (ใช้ข้อมูลส่วนตัวของคุณ)
print("⏳ Downloading Dataset: valov3 v4...")
rf = Roboflow(api_key="6sZ7LChGD2lbSp61IDVz")
project = rf.workspace("xdsf").project("valov3")
version = project.version(4)
dataset = version.download("yolov8")

# 3. ตั้งค่าการเทรน (Best Quality at 320)
TRAIN_SIZE = 320
EPOCHS = 100

print(f"🚀 เริ่มเทรนโมเดลหลักที่ขนาด {TRAIN_SIZE}...")
model = YOLO('yolov8n.pt') 

results = model.train(
    data=f"{dataset.location}/data.yaml", 
    epochs=EPOCHS, 
    imgsz=TRAIN_SIZE, 
    batch=32, 
    device=0, 
    name="valo_v3_master"
)

# 4. โหลด Weights มาแปลงร่างเป็น 3 ขนาด (INT8)
best_model_path = "runs/detect/valo_v3_master/weights/best.pt"
master_model = YOLO(best_model_path)

resolutions = [192, 256, 320]
export_files = []

print("\n--- 📦 กำลังเริ่มขั้นตอนการ Quantization (INT8) ---")
for res in resolutions:
    print(f"🛠️  กำลังสร้างโมเดลขนาด {res}x{res} (INT8)...")
    path = master_model.export(
        format="tflite", 
        imgsz=res, 
        int8=True, 
        data=f"{dataset.location}/data.yaml"
    )
    
    # เปลี่ยนชื่อไฟล์ให้จำง่าย
    new_name = f"valo_v3_{res}_int8.tflite"
    if os.path.exists(new_name): os.remove(new_name)
    os.rename(path, new_name)
    export_files.append(new_name)

# 5. รวมไฟล์เป็น ZIP เพื่อดาวน์โหลด
print("\n📦 กำลังบีบอัดไฟล์ทั้งหมด...")
zip_name = "valo_v3_models_int8.zip"
files_to_zip = " ".join(export_files)
os.system(f"zip -j {zip_name} {files_to_zip}")

print(f"\n🎉 เสร็จสมบูรณ์! ดาวน์โหลดไฟล์ '{zip_name}' ไปใช้งานได้เลยครับ")
