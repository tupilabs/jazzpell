package br.com.paskuale.jazzpell;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HunspellDictionary {

	  static final HunspellWord NOFLAGS = new HunspellWord();
	  
	  private static final String PREFIX_KEY = "PFX";
	  private static final String SUFFIX_KEY = "SFX";
	  private static final String FLAG_KEY = "FLAG";

	  private static final String NUM_FLAG_TYPE = "num";
	  private static final String UTF8_FLAG_TYPE = "UTF-8";
	  private static final String LONG_FLAG_TYPE = "long";
	  
	  private static final String PREFIX_CONDITION_REGEX_PATTERN = "%s.*";
	  private static final String SUFFIX_CONDITION_REGEX_PATTERN = ".*%s";

	  private CharArrayMap<List<HunspellWord>> words;
	  private CharArrayMap<List<HunspellAffix>> prefixes;
	  private CharArrayMap<List<HunspellAffix>> suffixes;

	  private FlagParsingStrategy flagParsingStrategy = new SimpleFlagParsingStrategy(); // Default flag parsing strategy

	  /**
	   * Creates a new HunspellDictionary containing the information read from the provided InputStreams to hunspell affix
	   * and dictionary files
	   *
	   * @param affix InputStream for reading the hunspell affix file
	   * @param dictionary InputStream for reading the hunspell dictionary file
	   * @throws IOException Can be thrown while reading from the InputStreams
	   * @throws ParseException Can be thrown if the content of the files does not meet expected formats
	   */
	  public HunspellDictionary(InputStream affix, InputStream dictionary) throws IOException, ParseException {
	    this(affix, Arrays.asList(new InputStream[] { dictionary }));
	  }

	  /**
	   * Creates a new HunspellDictionary containing the information read from the provided InputStreams to hunspell affix
	   * and dictionary files
	   *
	   * @param affix InputStream for reading the hunspell affix file
	   * @param dictionaries InputStreams for reading the hunspell dictionary file
	   * @throws IOException Can be thrown while reading from the InputStreams
	   * @throws ParseException Can be thrown if the content of the files does not meet expected formats
	   */
	  public HunspellDictionary(InputStream affix, List<InputStream> dictionaries) throws IOException, ParseException {
	    String encoding = getDictionaryEncoding(affix);
	    CharsetDecoder decoder = getJavaEncoding(encoding);
	    readAffixFile(affix, decoder);
	    words = new CharArrayMap<List<HunspellWord>>(65535 /* guess */, false);
	    for (InputStream dictionary : dictionaries)
	      readDictionaryFile(dictionary, decoder);
	  }

	  /**
	   * Looks up HunspellWords that match the String created from the given char array, offset and length
	   *
	   * @param word Char array to generate the String from
	   * @param offset Offset in the char array that the String starts at
	   * @param length Length from the offset that the String is
	   * @return List of HunspellWords that match the generated String, or {@code null} if none are found
	   */
	  public List<HunspellWord> lookupWord(char word[], int offset, int length) {
	    return words.get(word, offset, length);
	  }

	  /**
	   * Looks up HunspellAffix prefixes that have an append that matches the String created from the given char array, offset and length
	   *
	   * @param word Char array to generate the String from
	   * @param offset Offset in the char array that the String starts at
	   * @param length Length from the offset that the String is
	   * @return List of HunspellAffix prefixes with an append that matches the String, or {@code null} if none are found
	   */
	  public List<HunspellAffix> lookupPrefix(char word[], int offset, int length) {
	    return prefixes.get(word, offset, length);
	  }

	  /**
	   * Looks up HunspellAffix suffixes that have an append that matches the String created from the given char array, offset and length
	   *
	   * @param word Char array to generate the String from
	   * @param offset Offset in the char array that the String starts at
	   * @param length Length from the offset that the String is
	   * @return List of HunspellAffix suffixes with an append that matches the String, or {@code null} if none are found
	   */
	  public List<HunspellAffix> lookupSuffix(char word[], int offset, int length) {
	    return suffixes.get(word, offset, length);
	  }

	  // ================================================= Helper Methods ================================================

	  /**
	   * Reads the affix file through the provided InputStream, building up the prefix and suffix maps
	   *
	   * @param affixStream InputStream to read the content of the affix file from
	   * @param decoder CharsetDecoder to decode the content of the file
	   * @throws IOException Can be thrown while reading from the InputStream
	   */
	  private void readAffixFile(InputStream affixStream, CharsetDecoder decoder) throws IOException {
	    prefixes = new CharArrayMap<List<HunspellAffix>>(8, false);
	    suffixes = new CharArrayMap<List<HunspellAffix>>(8, false);
	    
	    BufferedReader reader = new BufferedReader(new InputStreamReader(affixStream, decoder));
	    String line = null;
	    while ((line = reader.readLine()) != null) {
	      if (line.startsWith(PREFIX_KEY)) {
	        parseAffix(prefixes, line, reader, PREFIX_CONDITION_REGEX_PATTERN);
	      } else if (line.startsWith(SUFFIX_KEY)) {
	        parseAffix(suffixes, line, reader, SUFFIX_CONDITION_REGEX_PATTERN);
	      } else if (line.startsWith(FLAG_KEY)) {
	        // Assume that the FLAG line comes before any prefix or suffixes
	        // Store the strategy so it can be used when parsing the dic file
	        flagParsingStrategy = getFlagParsingStrategy(line);
	      }
	    }
	    reader.close();
	  }

	  /**
	   * Parses a specific affix rule putting the result into the provided affix map
	   * 
	   * @param affixes Map where the result of the parsing will be put
	   * @param header Header line of the affix rule
	   * @param reader BufferedReader to read the content of the rule from
	   * @param conditionPattern {@link String#format(String, Object...)} pattern to be used to generate the condition regex
	   *                         pattern
	   * @throws IOException Can be thrown while reading the rule
	   */
	  private void parseAffix(CharArrayMap<List<HunspellAffix>> affixes,
	                          String header,
	                          BufferedReader reader,
	                          String conditionPattern) throws IOException {
	    String args[] = header.split("\\s+");

	    boolean crossProduct = args[2].equals("Y");
	    
	    int numLines = Integer.parseInt(args[3]);
	    for (int i = 0; i < numLines; i++) {
	      String line = reader.readLine();
	      String ruleArgs[] = line.split("\\s+");

	      HunspellAffix affix = new HunspellAffix();
	      
	      affix.setFlag(flagParsingStrategy.parseFlag(ruleArgs[1]));
	      affix.setStrip(ruleArgs[2].equals("0") ? "" : ruleArgs[2]);

	      String affixArg = ruleArgs[3];
	      
	      int flagSep = affixArg.lastIndexOf('/');
	      if (flagSep != -1) {
	        char appendFlags[] = flagParsingStrategy.parseFlags(affixArg.substring(flagSep + 1));
	        Arrays.sort(appendFlags);
	        affix.setAppendFlags(appendFlags);
	        affix.setAppend(affixArg.substring(0, flagSep));
	      } else {
	        affix.setAppend(affixArg);
	      }


	      String condition = ruleArgs[4];
	      affix.setCondition(condition, String.format(conditionPattern, condition));
	      affix.setCrossProduct(crossProduct);
	      
	      List<HunspellAffix> list = affixes.get(affix.getAppend());
	      if (list == null) {
	        list = new ArrayList<HunspellAffix>();
	        affixes.put(affix.getAppend(), list);
	      }
	      
	      list.add(affix);
	    }
	  }

	  /**
	   * Parses the encoding specificed in the affix file readable through the provided InputStream
	   *
	   * @param affix InputStream for reading the affix file
	   * @return Encoding specified in the affix file
	   * @throws IOException Can be thrown while reading from the InputStream
	   * @throws ParseException Thrown if the first non-empty non-comment line read from the file does not adhere to the format {@code SET <encoding>}
	   */
	  private String getDictionaryEncoding(InputStream affix) throws IOException, ParseException {
	    final StringBuilder encoding = new StringBuilder();
	    for (;;) {
	      encoding.setLength(0);
	      int ch;
	      while ((ch = affix.read()) >= 0) {
	        if (ch == '\n') {
	          break;
	        }
	        if (ch != '\r') {
	          encoding.append((char)ch);
	        }
	      }
	      if (
	          encoding.length() == 0 || encoding.charAt(0) == '#' ||
	          // this test only at the end as ineffective but would allow lines only containing spaces:
	          encoding.toString().trim().length() == 0
	      ) {
	        if (ch < 0)
	          throw new ParseException("Unexspected end of affix file.", 0);
	        continue;
	      }
	      if ("SET ".equals(encoding.substring(0, 4))) {
	        // cleanup the encoding string, too (whitespace)
	        return encoding.substring(4).trim();
	      }
	      throw new ParseException("The first non-comment line in the affix file must "+
	          "be a 'SET charset', was: '" + encoding +"'", 0);
	    }
	  }

	  /**
	   * Retrieves the CharsetDecoder for the given encoding.  Note, This isn't perfect as I think ISCII-DEVANAGARI and
	   * MICROSOFT-CP1251 etc are allowed...
	   *
	   * @param encoding Encoding to retrieve the CharsetDecoder for
	   * @return CharSetDecoder for the given encoding
	   */
	  private CharsetDecoder getJavaEncoding(String encoding) {
	    Charset charset = Charset.forName(encoding);
	    return charset.newDecoder();
	  }

	  /**
	   * Determines the appropriate {@link FlagParsingStrategy} based on the FLAG definiton line taken from the affix file
	   *
	   * @param flagLine Line containing the flag information
	   * @return FlagParsingStrategy that handles parsing flags in the way specified in the FLAG definiton
	   */
	  private FlagParsingStrategy getFlagParsingStrategy(String flagLine) {
	    String flagType = flagLine.substring(5);

	    if (NUM_FLAG_TYPE.equals(flagType)) {
	      return new NumFlagParsingStrategy();
	    } else if (UTF8_FLAG_TYPE.equals(flagType)) {
	      return new SimpleFlagParsingStrategy();
	    } else if (LONG_FLAG_TYPE.equals(flagType)) {
	      return new DoubleASCIIFlagParsingStrategy();
	    }

	    throw new IllegalArgumentException("Unknown flag type: " + flagType);
	  }

	  /**
	   * Reads the dictionary file through the provided InputStream, building up the words map
	   *
	   * @param dictionary InputStream to read the dictionary file through
	   * @param decoder CharsetDecoder used to decode the contents of the file
	   * @throws IOException Can be thrown while reading from the file
	   */
	  private void readDictionaryFile(InputStream dictionary, CharsetDecoder decoder) throws IOException {
	    BufferedReader reader = new BufferedReader(new InputStreamReader(dictionary, decoder));
	    // nocommit, don't create millions of strings.
	    String line = reader.readLine(); // first line is number of entries
	    int numEntries = Integer.parseInt(line);
	    
	    // nocommit, the flags themselves can be double-chars (long) or also numeric
	    // either way the trick is to encode them as char... but they must be parsed differently
	    while ((line = reader.readLine()) != null) {
	      String entry;
	      HunspellWord wordForm;
	      
	      int flagSep = line.lastIndexOf('/');
	      if (flagSep == -1) {
	        wordForm = NOFLAGS;
	        entry = line;
	      } else {
	        // note, there can be comments (morph description) after a flag.
	        // we should really look for any whitespace
	        int end = line.indexOf('\t', flagSep);
	        if (end == -1)
	          end = line.length();
	        
	        
	        wordForm = new HunspellWord(flagParsingStrategy.parseFlags(line.substring(flagSep + 1, end)));
	        Arrays.sort(wordForm.getFlags());
	        entry = line.substring(0, flagSep);
	      }
	      
	      List<HunspellWord> entries = words.get(entry);
	      if (entries == null) {
	        entries = new ArrayList<HunspellWord>();
	        words.put(entry, entries);
	      }
	      entries.add(wordForm);
	    }
	  }

	  // ================================================= Inner Classes =================================================

	  /**
	   * Abstraction of the process of parsing flags taken from the affix and dic files
	   */
	  private static abstract class FlagParsingStrategy {

	    /**
	     * Parses the given String into a single flag
	     *
	     * @param rawFlag String to parse into a flag
	     * @return Parsed flag
	     */
	    char parseFlag(String rawFlag) {
	      return parseFlags(rawFlag)[0];
	    }

	    /**
	     * Parses the given String into multiple flags
	     *
	     * @param rawFlags String to parse into flags
	     * @return Parsed flags
	     */
	    abstract char[] parseFlags(String rawFlags);
	  }

	  /**
	   * Simple implementation of {@link FlagParsingStrategy} that treats the chars in each String as a individual flags.
	   * Can be used with both the ASCII and UTF-8 flag types.
	   */
	  private static class SimpleFlagParsingStrategy extends FlagParsingStrategy {
	    /**
	     * {@inheritDoc}
	     */
	    public char[] parseFlags(String rawFlags) {
	      return rawFlags.toCharArray();
	    }
	  }

	  /**
	   * Implementation of {@link FlagParsingStrategy} that assumes each flag is encoded in its numerical form.  In the case
	   * of multiple flags, each number is separated by a comma.
	   */
	  private static class NumFlagParsingStrategy extends FlagParsingStrategy {
	    /**
	     * {@inheritDoc}
	     */
	    public char[] parseFlags(String rawFlags) {
	      String[] rawFlagParts = rawFlags.trim().split(",");
	      char[] flags = new char[rawFlagParts.length];

	      for (int i = 0; i < rawFlagParts.length; i++) {
	        // note, removing the trailing X/leading I for nepali... what is the rule here?! 
	        flags[i] = (char) Integer.parseInt(rawFlagParts[i].replaceAll("[^0-9]", ""));
	      }

	      return flags;
	    }
	  }

	  /**
	   * Implementation of {@link FlagParsingStrategy} that assumes each flag is encoded as two ASCII characters whose codes
	   * must be combined into a single character.
	   *
	   * TODO (rmuir) test
	   */
	  private static class DoubleASCIIFlagParsingStrategy extends FlagParsingStrategy {

	    /**
	     * {@inheritDoc}
	     */
	    public char[] parseFlags(String rawFlags) {
	      if (rawFlags.length() == 0) {
	        return new char[0];
	      }

	      StringBuilder builder = new StringBuilder();
	      for (int i = 0; i < rawFlags.length(); i+=2) {
	        char cookedFlag = (char) ((int) rawFlags.charAt(i) + (int) rawFlags.charAt(i + 1));
	        builder.append(cookedFlag);
	      }
	      
	      char flags[] = new char[builder.length()];
	      builder.getChars(0, builder.length(), flags, 0);
	      return flags;
	    }
	  }
	}
