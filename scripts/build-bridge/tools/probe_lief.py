import lief

print([x for x in dir(lief.ELF.Binary) if not x.startswith("_")])
bin = lief.ELF.Binary("libfu_ai_extras", lief.ELF.ELF_CLASS.CLASS64)
print("created", bin)
print("file_type", bin.header.file_type)
print("machine", bin.header.machine_type)
