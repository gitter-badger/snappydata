#!/usr/bin/env bash

# This utility moves markdown files to the docs folder for 
# generating documentation and then call the mkdocs utility 
# with the passed arguments.  

# Copy md files to the docs folder 
cp -R featureDocs/ ./docs/

# In place replace README.md with index.md in all the md files in featureDocs
FDIR="./docs/featureDocs"
for f in `find ${FDIR} -name "*.md"`
do
  #echo REPLACING IN ${f}
  sed -i 's/README.md/index.md/' ${f}
done

## Remove the lines till toc and then copy that as index.md
index_start_line=`grep -n '## Introduction' README.md | cut -d':' -f1`
echo LINE START $index_start_line

if [ ! -z ${index_start_line} ]; then
  tail -n +$index_start_line README.md > ./docs/index.md
else
  echo "Did not find the Introduction line in README.md"
  exit 1
fi

# Copy the README.md also as is as the reference to this file from anywhere
# else will not be broken
#cp ./docs/index.md ./docs/README.md 

# call the mkdocs utility
MKDOCS_EXISTS=`which mkdocs`

if [ -z ${MKDOCS_EXISTS} ]; then
  echo "Install mkdocs...exiting"
  exit 1
fi

#echo $@
#mkdocs $@
mkdocs build --clean
mkdocs gh-deploy

# remove extra files added to docs
rm -rf ./docs/featureDocs
rm ./docs/index.md
#mkdocs serve &
