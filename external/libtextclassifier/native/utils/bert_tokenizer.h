/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef LIBTEXTCLASSIFIER_UTILS_BERT_TOKENIZER_H_
#define LIBTEXTCLASSIFIER_UTILS_BERT_TOKENIZER_H_

#include <fstream>
#include <string>
#include <vector>

#include "annotator/types.h"
#include "utils/wordpiece_tokenizer.h"
#include "absl/container/flat_hash_map.h"
#include "tensorflow_lite_support/cc/text/tokenizers/tokenizer.h"
#include "tensorflow_lite_support/cc/utils/common_utils.h"

namespace libtextclassifier3 {

using ::tflite::support::text::tokenizer::TokenizerResult;
using ::tflite::support::utils::LoadVocabFromBuffer;
using ::tflite::support::utils::LoadVocabFromFile;

constexpr int kDefaultMaxBytesPerToken = 100;
constexpr int kDefaultMaxCharsPerSubToken = 100;
constexpr char kDefaultSuffixIndicator[] = "##";
constexpr bool kDefaultUseUnknownToken = true;
constexpr char kDefaultUnknownToken[] = "[UNK]";
constexpr bool kDefaultSplitUnknownChars = false;

// Result of wordpiece tokenization including subwords and offsets.
// Example:
// input:                tokenize     me  please
// subwords:             token ##ize  me  plea ##se
// wp_begin_offset:     [0,      5,   9,  12,    16]
// wp_end_offset:       [     5,    8,  11,   16,  18]
// row_lengths:         [2,          1,  1]
struct WordpieceTokenizerResult
    : tflite::support::text::tokenizer::TokenizerResult {
  std::vector<int> wp_begin_offset;
  std::vector<int> wp_end_offset;
  std::vector<int> row_lengths;
};

// Options to create a BertTokenizer.
struct BertTokenizerOptions {
  int max_bytes_per_token = kDefaultMaxBytesPerToken;
  int max_chars_per_subtoken = kDefaultMaxCharsPerSubToken;
  std::string suffix_indicator = kDefaultSuffixIndicator;
  bool use_unknown_token = kDefaultUseUnknownToken;
  std::string unknown_token = kDefaultUnknownToken;
  bool split_unknown_chars = kDefaultSplitUnknownChars;
};

// A flat-hash-map based implementation of WordpieceVocab, used in
// BertTokenizer to invoke tensorflow::text::WordpieceTokenize within.
class FlatHashMapBackedWordpiece : public WordpieceVocab {
 public:
  explicit FlatHashMapBackedWordpiece(const std::vector<std::string>& vocab);

  LookupStatus Contains(absl::string_view key, bool* value) const override;
  bool LookupId(absl::string_view key, int* result) const;
  bool LookupWord(int vocab_id, absl::string_view* result) const;
  int VocabularySize() const { return vocab_.size(); }

 private:
  // All words indexed position in vocabulary file.
  std::vector<std::string> vocab_;
  absl::flat_hash_map<absl::string_view, int> index_map_;
};

// Wordpiece tokenizer for bert models. Initialized with a vocab file or vector.
//
// The full tokenization involves two steps: Splitting the input into tokens
// (pretokenization) and splitting the tokens into subwords.
class BertTokenizer : public tflite::support::text::tokenizer::Tokenizer {
 public:
  // Initialize the tokenizer from vocab vector and tokenizer configs.
  explicit BertTokenizer(const std::vector<std::string>& vocab,
                         const BertTokenizerOptions& options = {})
      : vocab_{FlatHashMapBackedWordpiece(vocab)}, options_{options} {}

  // Initialize the tokenizer from file path to vocab and tokenizer configs.
  explicit BertTokenizer(const std::string& path_to_vocab,
                         const BertTokenizerOptions& options = {})
      : BertTokenizer(LoadVocabFromFile(path_to_vocab), options) {}

  // Initialize the tokenizer from buffer and size of vocab and tokenizer
  // configs.
  BertTokenizer(const char* vocab_buffer_data, size_t vocab_buffer_size,
                const BertTokenizerOptions& options = {})
      : BertTokenizer(LoadVocabFromBuffer(vocab_buffer_data, vocab_buffer_size),
                      options) {}

  // Perform tokenization, first tokenize the input and then find the subwords.
  // Return tokenized results containing the subwords.
  TokenizerResult Tokenize(const std::string& input) override;

  // Perform tokenization, first tokenize the input and then find the subwords.
  // Return tokenized results containing the subwords and codepoint indices.
  WordpieceTokenizerResult TokenizeIntoWordpieces(const std::string& input);

  // Perform tokenization on a single token, return tokenized results containing
  // the subwords and codepoint indices.
  WordpieceTokenizerResult TokenizeSingleToken(const std::string& token);

  // Perform tokenization, return tokenized results containing the subwords and
  // codepoint indices.
  WordpieceTokenizerResult TokenizeIntoWordpieces(
      const std::vector<Token>& tokens);

  // Check if a certain key is included in the vocab.
  LookupStatus Contains(const absl::string_view key, bool* value) const {
    return vocab_.Contains(key, value);
  }

  // Find the id of a wordpiece.
  bool LookupId(absl::string_view key, int* result) const override {
    return vocab_.LookupId(key, result);
  }

  // Find the wordpiece from an id.
  bool LookupWord(int vocab_id, absl::string_view* result) const override {
    return vocab_.LookupWord(vocab_id, result);
  }

  int VocabularySize() const { return vocab_.VocabularySize(); }

  static std::vector<std::string> PreTokenize(const absl::string_view input);

 private:
  FlatHashMapBackedWordpiece vocab_;
  BertTokenizerOptions options_;
};

}  // namespace libtextclassifier3

#endif  // LIBTEXTCLASSIFIER_UTILS_BERT_TOKENIZER_H_
