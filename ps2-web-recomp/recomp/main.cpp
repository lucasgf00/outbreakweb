#include "analysis.hpp"
#include "codegen.hpp"
#include "elf.hpp"
#include "overlay.hpp"
#include "snr2.hpp"

#include <algorithm>
#include <stdexcept>
#include <sstream>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <map>
#include <set>
#include <string>
#include <vector>

using namespace recomp;

namespace {

struct Entries {
    std::vector<std::uint32_t> main;
    std::map<std::string, std::vector<std::uint32_t>> modules;
};

Entries read_entries(const std::string &path) {
    Entries entries;
    std::ifstream file(path);
    std::string token;
    while (file >> token) {
        if (token[0] == '#') { std::getline(file, token); continue; }
        const auto colon = token.find(':');
        if (colon == std::string::npos)
            entries.main.push_back(static_cast<std::uint32_t>(std::stoul(token, nullptr, 16)));
        else
            entries.modules[token.substr(0, colon)].push_back(static_cast<std::uint32_t>(std::stoul(token.substr(colon + 1), nullptr, 16)));
    }
    return entries;
}

std::set<std::uint32_t> dynamic_sites(const Links &links) {
    std::set<std::uint32_t> sites;
    for (const auto &[at, link] : links)
        if (link.kind == Fix::Jump && link.external) sites.insert(at);
    return sites;
}

std::size_t code_size(const Program &program) {
    std::size_t code = 0;
    for (std::uint32_t pc = program.base; pc < program.end; pc += 4) code += program.is_code(pc);
    return code;
}

// Coverage of an overlay: text words, words translated, and the functions the analysis left out. A function
// start is an `addiu sp, sp, -N` (or daddiu) right after a `jr ra` and its delay slot (padding allowed), the
// same pattern frame_starts() seeds with; one that is not code here would run in the interpreter.
// The main program reports as "main", over the text its analysis searched (Program::text_begin/text_end).
void print_coverage(const std::string &name, std::uint32_t text_begin, std::uint32_t text_end, const Image &image, const Program &p) {
    std::size_t text = 0, code = 0, starts = 0, missing = 0;
    for (std::uint32_t pc = text_begin; pc < text_end; pc += 4) {
        ++text;
        code += p.is_code(pc);
        const std::uint32_t w = image.word(pc).value_or(0);
        if ((w & 0xFFFF8000u) != 0x27BD8000u && (w & 0xFFFF8000u) != 0x67BD8000u) continue;
        std::uint32_t at = pc - 4;
        while (at > text_begin + 4 && pc - at < 20 && image.word(at).value_or(1) == 0) at -= 4;
        if (image.word(at).value_or(0) != 0x03E00008u && image.word(at - 4).value_or(0) != 0x03E00008u) continue;
        ++starts;
        if (!p.is_code(pc)) ++missing;
    }
    std::printf("  %s: text %zu words, code %zu (%.1f%%), function starts %zu, untranslated %zu\n", name.c_str(), text, code,
                text ? 100.0 * code / text : 0.0, starts, missing);
    // EERECOMP_GAPS=<overlay name>: list the stretches of text left out that hold nonzero words.
    const char *gaps = std::getenv("EERECOMP_GAPS");
    if (!gaps || name != gaps) return;
    for (std::uint32_t pc = text_begin; pc < text_end;) {
        if (p.is_code(pc)) { pc += 4; continue; }
        std::uint32_t end = pc, nonzero = 0;
        while (end < text_end && !p.is_code(end)) nonzero += image.word(end).value_or(0) != 0, end += 4;
        if (nonzero) std::printf("    gap %08X..%08X %u words, %u nonzero\n", pc, end, (end - pc) / 4, nonzero);
        pc = end;
    }
}

std::uint32_t word_at(const Snr2Module &m, std::uint32_t at) {
    std::uint32_t value;
    std::memcpy(&value, m.bytes.data() + at, 4);
    return value;
}

std::vector<std::pair<std::uint32_t, std::uint32_t>> checks_for(const Snr2Module &m, const Program &program) {
    std::vector<std::uint32_t> words;
    for (std::uint32_t pc = m.code_begin; pc + 4 <= m.code_end; pc += 4)
        if (program.is_code(pc) && !m.links.count(pc) && word_at(m, pc)) words.push_back(pc);
    std::vector<std::pair<std::uint32_t, std::uint32_t>> checks{{0, 0x32524E53u}};
    for (std::size_t k = 0; k < 8 && !words.empty(); ++k) {
        const std::uint32_t pc = words[k * words.size() / 8];
        if (checks.back().first != pc) checks.emplace_back(pc, word_at(m, pc));
    }
    return checks;
}

std::vector<Snr2Module> read_modules(const std::string &dir) {
    std::vector<std::filesystem::path> paths;
    for (const auto &entry : std::filesystem::directory_iterator(dir))
        if (entry.is_regular_file()) paths.push_back(entry.path());
    std::sort(paths.begin(), paths.end());
    std::vector<Snr2Module> modules;
    std::map<std::string, int> names;
    for (const auto &path : paths) {
        std::ifstream file(path, std::ios::binary);
        std::vector<std::uint8_t> bytes{std::istreambuf_iterator<char>(file), {}};
        auto module = read_snr2_module(std::move(bytes));
        if (!module) continue;
        if (std::any_of(modules.begin(), modules.end(), [&](const Snr2Module &m) { return m.bytes == module->bytes; })) continue;
        if (const int n = names[module->name]++) module->name += "_" + std::to_string(n + 1);
        modules.push_back(std::move(*module));
    }
    return modules;
}

std::vector<std::pair<std::uint32_t, std::uint32_t>> overlay_checks(const Overlay &o, const Program &program) {
    std::vector<std::pair<std::uint32_t, std::uint32_t>> checks;
    const auto word = [&](std::uint32_t offset) {
        std::uint32_t value = 0;
        std::memcpy(&value, o.bytes.data() + offset, 4);
        return value;
    };
    for (std::uint32_t offset : o.identity) checks.emplace_back(offset, word(offset));
    std::vector<std::uint32_t> words;
    for (std::uint32_t pc = o.text_begin; pc + 4 <= o.text_end; pc += 4)
        if (program.is_code(pc) && word(pc - o.base)) words.push_back(pc - o.base);
    for (std::size_t k = 0; k < 8 && !words.empty(); ++k) {
        const std::uint32_t offset = words[k * words.size() / 8];
        if (checks.back().first != offset) checks.emplace_back(offset, word(offset));
    }
    return checks;
}

std::vector<Overlay> read_overlays(const std::string &dir) {
    std::vector<std::filesystem::path> paths;
    for (const auto &entry : std::filesystem::directory_iterator(dir))
        if (entry.is_regular_file()) paths.push_back(entry.path());
    std::sort(paths.begin(), paths.end());
    std::vector<Overlay> overlays;
    std::map<std::string, int> names;
    for (const auto &path : paths) {
        std::ifstream file(path, std::ios::binary);
        auto overlay = read_overlay(std::vector<std::uint8_t>{std::istreambuf_iterator<char>(file), {}});
        if (!overlay) continue;
        if (std::any_of(overlays.begin(), overlays.end(), [&](const Overlay &o) { return o.bytes == overlay->bytes; })) continue;
        if (const int n = names[overlay->name]++) overlay->name += "_" + std::to_string(n + 1);
        overlays.push_back(std::move(*overlay));
    }
    return overlays;
}

// Code patches for overlays, applied only to the image that gets translated: the identity checks still come
// from the original bytes, which is what the game keeps in memory. One patch per line:
//   <overlay name>:<hex address> <hex word> [<hex word> ...]   # comment
std::map<std::string, std::vector<std::pair<std::uint32_t, std::uint32_t>>> read_patches(const std::string &path) {
    std::map<std::string, std::vector<std::pair<std::uint32_t, std::uint32_t>>> patches;
    std::ifstream file(path);
    if (!file) throw std::runtime_error("cannot read " + path);
    std::string line;
    while (std::getline(file, line)) {
        if (const auto hash = line.find('#'); hash != std::string::npos) line.resize(hash);
        std::istringstream in(line);
        std::string where;
        if (!(in >> where)) continue;
        const auto colon = where.rfind(':');
        if (colon == std::string::npos) throw std::runtime_error("bad patch line: " + line);
        std::uint32_t at = static_cast<std::uint32_t>(std::stoul(where.substr(colon + 1), nullptr, 16));
        std::string word;
        while (in >> word) {
            patches[where.substr(0, colon)].emplace_back(at, static_cast<std::uint32_t>(std::stoul(word, nullptr, 16)));
            at += 4;
        }
    }
    return patches;
}

int usage() {
    std::fprintf(stderr, "usage: eerecomp <elf> [--entries file] [--modules dir] [--overlays dir] [--patches file] [--probes file] [--out dir] [--stats]\n");
    return 2;
}

}

int main(int argc, char **argv) {
    if (argc < 2) return usage();
    std::string elf_path = argv[1], entries_path, out, probes_path, modules_dir, overlays_dir, patches_path;
    bool stats = false;
    for (int i = 2; i < argc; ++i) {
        const std::string arg = argv[i];
        if (arg == "--entries" && i + 1 < argc) entries_path = argv[++i];
        else if (arg == "--stats") stats = true;
        else if (arg == "--out" && i + 1 < argc) out = argv[++i];
        else if (arg == "--probes" && i + 1 < argc) probes_path = argv[++i];
        else if (arg == "--modules" && i + 1 < argc) modules_dir = argv[++i];
        else if (arg == "--overlays" && i + 1 < argc) overlays_dir = argv[++i];
        else if (arg == "--patches" && i + 1 < argc) patches_path = argv[++i];
        else return usage();
    }

    try {
        const Image image = Image::load(elf_path);
        const Entries entries = entries_path.empty() ? Entries{} : read_entries(entries_path);
        const Links imports = snr2_imports(image);

        // Overlays are linked against the program and call into it, and into each other, directly: their call
        // targets hint at entry points the program's own analysis cannot see, and the program's calls into
        // overlay addresses do the same for the overlays.
        const std::vector<Overlay> overlays = overlays_dir.empty() ? std::vector<Overlay>{} : read_overlays(overlays_dir);
        std::vector<Image> overlay_images;
        std::vector<std::vector<std::uint32_t>> overlay_seeds;
        std::vector<std::uint32_t> hints;
        const auto patches = patches_path.empty() ? decltype(read_patches("")){} : read_patches(patches_path);
        for (const Overlay &o : overlays) {
            Overlay patched = o;
            if (const auto it = patches.find(o.name); it != patches.end()) {
                for (const auto &[at, word] : it->second) {
                    if (at < o.base || at - o.base + 4 > patched.bytes.size()) throw std::runtime_error("patch outside " + o.name);
                    std::memcpy(patched.bytes.data() + (at - o.base), &word, 4);
                }
                std::printf("%s: %zu patched words\n", o.name.c_str(), it->second.size());
            }
            overlay_images.push_back(overlay_image(patched));
            overlay_seeds.push_back(o.seeds);
            if (const auto it = entries.modules.find(o.name); it != entries.modules.end())
                overlay_seeds.back().insert(overlay_seeds.back().end(), it->second.begin(), it->second.end());
            const std::vector<std::uint32_t> targets = call_targets(overlay_images.back(), analyse(overlay_images.back(), overlay_seeds.back()));
            hints.insert(hints.end(), targets.begin(), targets.end());
        }

        Program program = analyse(image, entries.main, dynamic_sites(imports), hints);
        if (!overlays.empty()) {
            const std::vector<std::uint32_t> targets = call_targets(image, program);
            hints.insert(hints.end(), targets.begin(), targets.end());
        }
        std::map<Op, std::size_t> histogram;
        for (std::uint32_t pc = program.base; pc < program.end; pc += 4)
            if (program.is_code(pc)) ++histogram[decode(*image.word(pc), pc).op];
        const std::size_t code = code_size(program);
        std::printf("code %zu instructions (%zu KiB), functions %zu, entries %zu, rejected seeds %zu\n",
                    code, code * 4 / 1024, program.functions.size(), program.entries.size(), program.rejected.size());
        if (stats)
            for (const auto &[op, count] : histogram) std::printf("%10zu %s\n", count, name(op));
        print_coverage("main", program.text_begin, program.text_end, image, program);

        std::vector<Translation> translations{{&image, &program, imports.empty() ? nullptr : &imports, "", false, "", 0, {}}};
        const std::vector<Snr2Module> modules = modules_dir.empty() ? std::vector<Snr2Module>{} : read_modules(modules_dir);
        std::deque<Image> images;
        std::deque<Program> programs;
        std::size_t module_code = 0;
        for (const Snr2Module &m : modules) {
            images.push_back(snr2_image(m));
            std::vector<std::uint32_t> seeds = m.seeds;
            if (const auto it = entries.modules.find(m.name); it != entries.modules.end()) seeds.insert(seeds.end(), it->second.begin(), it->second.end());
            programs.push_back(analyse(images.back(), seeds, dynamic_sites(m.links)));
            module_code += code_size(programs.back());
            translations.push_back({&images.back(), &programs.back(), &m.links, "m_" + m.name + "_", true, m.name, m.size, checks_for(m, programs.back())});
        }
        if (!modules.empty())
            std::printf("%zu modules, code %zu instructions (%zu KiB), %zu sites in the program linked at run time\n", modules.size(), module_code,
                        module_code * 4 / 1024, imports.size());

        std::size_t overlay_code = 0;
        for (std::size_t k = 0; k < overlays.size(); ++k) {
            const Overlay &o = overlays[k];
            images.push_back(std::move(overlay_images[k]));
            programs.push_back(analyse(images.back(), overlay_seeds[k], {}, hints));
            overlay_code += code_size(programs.back());
            print_coverage(o.name, o.text_begin, o.text_end, images.back(), programs.back());
            translations.push_back({&images.back(), &programs.back(), nullptr, "o_" + o.name + "_", false, o.name,
                                    static_cast<std::uint32_t>(o.bytes.size()), overlay_checks(o, programs.back()), o.base});
        }
        if (!overlays.empty())
            std::printf("%zu overlays, code %zu instructions (%zu KiB)\n", overlays.size(), overlay_code, overlay_code * 4 / 1024);

        if (!out.empty()) {
            CodegenOptions options;
            options.output = out;
            if (!probes_path.empty())
                for (std::uint32_t a : read_entries(probes_path).main) options.probes.insert(a);
            generate(translations, options);
        }
    } catch (const std::exception &error) {
        std::fprintf(stderr, "eerecomp: %s\n", error.what());
        return 1;
    }
    return 0;
}
