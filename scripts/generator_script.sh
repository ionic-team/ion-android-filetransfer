### Welcome the user to this awesome tool

printf "Welcome to the Android Library Generator!!\n\n"

remoteURL=$(git config --get remote.origin.url)
remoteURL=$(echo $remoteURL | perl -F/ -wane 'print $F[-1]')
remoteURL=$(echo ${remoteURL%-*})

### Inform the user of the library name to be used
printf "Considering the repository name you gave while creating it, we will use '$remoteURL' as the Library's name."

### Ask the user for the package identifier. Continue to ask while the input doesn't match the correct format.
while true
do
	printf "\nPlease write the desired package identifier. This should be composed only by lowercase letters, numbers and '.' (e.g. 'com.outsystems').\n"
    read organisationId
   	result=$(echo $organisationId | awk '/^[a-z0-9]+([\.]?[a-z0-9]+)*?$/')

   	if ! [ -z "$result" ]; then
   		break
   	else
   		printf "Format not valid. Please try again."
   	fi
done

### Delete current file
currentFile=$(basename "$0")

printf "\nThe '$currentFile' file will be removed as it should only be executed once.\n\n"

rm -f $currentFile

### Proceed to change all necessary placeholders
cd ..

LC_CTYPE=C && LANG=C && find . -type f -exec sed -e "s/LibTemplatePlaceholder/$remoteURL/g" -i '' '{}' ';'
LC_CTYPE=C && LANG=C && find . -depth -name '*LibTemplatePlaceholder*' -print0|while IFS= read -rd '' f; do mv -i "$f" "$(echo "$f"|sed -E "s/(.*)LibTemplatePlaceholder/\1$remoteURL/")"; done

LC_CTYPE=C && LANG=C && find . -type f -exec sed -e "s/organizationidplaceholder/$organisationId/g" -i '' '{}' ';'
LC_CTYPE=C && LANG=C && find . -depth -name '*organizationidplaceholder*' -print0|while IFS= read -rd '' f; do mv -i "$f" "$(echo "$f"|sed -E "s/(.*)organizationidplaceholder/\1$organisationId/")"; done

### Convert $remoteURL to lowercase
lowercaseRemoteURL=$(echo "$remoteURL" | tr '[:upper:]' '[:lower:]')

LC_CTYPE=C && LANG=C && find . -type f -exec sed -e "s/libtemplateplaceholder/$lowercaseRemoteURL/g" -i '' '{}' ';'
LC_CTYPE=C && LANG=C && find . -depth -name '*libtemplateplaceholder*' -print0|while IFS= read -rd '' f; do mv -i "$f" "$(echo "$f"|sed -E "s/(.*)libtemplateplaceholder/\1$lowercaseRemoteURL/")"; done

### Commit and push
rm -f .git/index
git reset
git add .
git commit -m "Finish initialisation by running the generator script."
git push

### Close
printf "\n###Applause###\n"
printf "Looks like everything's done. Enjoy!\n\n"
