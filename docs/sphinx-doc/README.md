### Sphinx-doc
​
This directory contains sources for building project documentation using Sphinx-doc.
​
​
#### How to use:

1. Create the conda environment `lets-plot-sphinx` from the file `environment.yml`:
    
    `conda env create -f docs/sphinx-doc/environment.yml`
    
    If you have this environment already, update it:
    
    `conda env update --prune -f docs/sphinx-doc/environment.yml`
    
2. Activate this environment:

    `conda activate lets-plot-sphinx`
    
3. Edit `build_settings.yml`:
   
    - set both `build_python_extension` and `enable_python_package` options to `yes`
    - set `bin_path` and `include_path` in the `Python settings` section to your conda environment paths.
​
4. Build `Lets-Plot python-package`: 

    `./gradlew python-package-build:build`
​
5. Then install it:
    
    `pip install --no-index --force-reinstall --find-links=python-package/dist/ lets-plot --no-deps`
​
6. Build documentation (HTML):  

    `sphinx-build -b html docs/sphinx-doc/source/ docs/sphinx-doc/build/`
​
7. Open `docs/sphinx-doc/build/index.html` in your browser.
​
​
#### Edit documentation using `autodoc` generator:
​
Autodoc generates documentation from Docstrings. Used with `napoleon` extension to parse NumPy docstrings.
Project already has generated autodoc structure for current Python modules: `docs/sphinx-doc/source/autodoc/`.
​
1. Activate conda environment `lets-plot-sphinx`:
​
    `conda activate lets-plot-sphinx`
​
2. To add a new module to the autodoc structure run:   
​  
    `sphinx-apidoc -f -o docs/sphinx-doc/source/autodoc/ python-package/lets_plot/<path_to_module>`
    
    New `*.rst` file with the module name will appear in the `docs/sphinx-doc/source/autodoc/`.  
    Add the path to this file in the `docs/sphinx-doc/source/index.rst`.
​
3. Edit docstrings in the `*.py` files of modules.
​
4. Build `Lets-Plot python-package`: reproduce the steps 3-5 from **How to use** section.
​
7. Build documentation (HTML):  

    `sphinx-build -b html docs/sphinx-doc/source docs/sphinx-doc/build/`
​
8. Open `docs/sphinx-doc/build/index.html` in your browser and check result.
​
​
#### Edit documentation using `reStructuredText` format:

1. Activate conda environment `lets-plot-sphinx`:
   
    `conda activate lets-plot-sphinx`
       
2. Create docs in `reStructuredText` format (files `*.rst`):   
   
   https://www.sphinx-doc.org/en/master/usage/restructuredtext/index.html
    
3. Put them in the `docs/sphinx-doc/source/` directory. Any additional directory can be created inside for the structure optimization.
​
4. Add paths to new docs in the `docs/sphinx-doc/source/index.rst` file.
​
5. Build documentation (HTML):  
   
    `sphinx-build -b html docs/sphinx-doc/source/ docs/sphinx-doc/build/`
   
6. Open `docs/sphinx-doc/build/index.html` in your browser and check result.
​
​
#### File structure:
     
 - `source/` - main source folder
 - `source/conf.py` - main config file
 - `source/index.rst` - source file for index.html (main page with content tree etc.)
 - `source/autodoc/` - directory with the generated 'autodoc' structure files.
 - `source/ref/` and `source/usage/` - directories with the examples of document sources.
 - `source/_static/` and `source/_templates/` - service directories (auto-generated). Can be used in the future.
 - `build/` - directory containing generated documentation as prepared structure. Will be added by executing `sphinx-build` command. **Ignored by Git.**
 - `jupyter-execute/` - directory generated by the `jupyter_sphinx` extension. Contain files `*.py` and `*.ipynb` (executed code from the documents sources). **Ignored by Git.**
 - `make.bat` and `Makefile` - build files for `make`. Generated by the `sphinx-quickstart` command.
​
​
#### Documentation:

1. Sphinx-doc: https://www.sphinx-doc.org/en/master/contents.html
2. Jupyter Sphinx Extension: https://jupyter-sphinx.readthedocs.io/en/latest/
3. Conda environments: https://docs.conda.io/projects/conda/en/latest/user-guide/tasks/manage-environments.html
